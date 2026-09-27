package com.hiyowa.tika.jtd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.DocumentEntry
import org.apache.poi.poifs.filesystem.DocumentInputStream
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.tika.extractor.EmbeddedDocumentExtractor
import org.apache.tika.extractor.EmbeddedDocumentUtil
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.parser.ParseContext
import org.xml.sax.ContentHandler

/**
 * 一太郎のオブジェクト枠（ObjectBox）からの埋め込みドキュメント抽出器。
 *
 * 一太郎のドキュメントモデルにおいて、本文（/DocumentText）やレイアウト枠（/LayoutBoxText）
 * とは別に文書中に配置された図形・表計算・OLE オブジェクト等の外部要素は「オブジェクト枠」
 * として CFB コンテナ内の `Embedding N` や `OleItem N` ストレージに格納される。
 *
 * 本クラスはこれらオブジェクト枠の構造を走査・特定し、Tika が解釈可能な形式へ整流化した上で、
 * Tika 4 標準の [EmbeddedDocumentExtractor] 契約（[EmbeddedDocumentExtractor.shouldParseEmbedded]
 * → [EmbeddedDocumentExtractor.parseEmbedded]）に基づいて親文書の本文ストリームへインライン結合する。
 *
 * 対応するオブジェクト枠ストリーム:
 * - 表計算 (`Workbook`): 生 BIFF8（先頭 BOF 0x0809・リトルエンディアン）は空の OLE2 CFB の
 *   "Workbook" エントリへラップして `embedded-N.xls` (`application/vnd.ms-excel`) として委譲。
 *   既に CFB コンテナの場合は無加工で委譲。
 * - 図形 press (`EmbeddedPress*`・先頭制御文字 \u0003 を含む場合あり): 先頭 8 バイトが
 *   "METAFILE" のもののみ対象。WMF ヘッダマーカー（type=0x0001・headerSize=9語）の位置から
 *   末尾までをスライスして `embedded-N.wmf` (`image/wmf`) として委譲。
 *
 * 堅牢性・フェイルセーフ設計:
 * - コンテナ開封失敗・個別オブジェクトの読み出し例外・委譲先パーサーの例外（SAXException, IOException 等）
 *   はすべて握り、親文書の本文出力を絶対に壊さない防壁設計とする。
 * - 通し番号 `N` は [EmbeddedDocumentExtractor.parseEmbedded] が実際に呼び出された時のみ進める
 *   （[EmbeddedDocumentExtractor.shouldParseEmbedded] で拒否された場合は消費しない）。
 * - 再帰上限 [MAX_EMBEDDED_DEPTH]（ルート直下を深さ 1 とする）、委譲上限 [MAX_EMBEDDED_OBJECTS]。
 */
object ObjectBoxExtractor {

    /** 図形 press ストリームの "METAFILE" マジックプレフィックス（先頭 8 バイト）。 */
    private val METAFILE_MAGIC = "METAFILE".toByteArray(Charsets.ISO_8859_1)

    /** CFB (OLE2) マジック。生データが既に CFB コンテナかを判定する。 */
    private val CFB_MAGIC = JtdContainerReader.CFB_MAGIC

    /** WMF ヘッダ先頭 4 バイト（type=0x0001, headerSize=9語 = 0x0009・リトルエンディアン）。 */
    private val WMF_HEADER_MAGIC = byteArrayOf(0x01, 0x00, 0x09, 0x00)

    /** EMF ヘッダ先頭 4 バイト（iType=0x00000001・リトルエンディアン）。 */
    private val EMF_HEADER_MAGIC = byteArrayOf(0x01, 0x00, 0x00, 0x00)

    /** EMF ヘッダオフセット 40..44 のシグネチャ (" EMF" = 0x28434D45)。 */
    private val EMF_SIGNATURE = " EMF".toByteArray(Charsets.ISO_8859_1)

    /** PNG マジック（先頭 4 バイト 0x89 'P' 'N' 'G'）。 */
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    /** GIF マジック（先頭 4 バイト "GIF8"）。 */
    private val GIF_MAGIC = "GIF8".toByteArray(Charsets.ISO_8859_1)

    /** 埋め込み走査の再帰上限（ルート直下を深さ 1 とする。マルチシート構成の EmbedItems 等の深さに対応するため 8 に設定）。 */
    private const val MAX_EMBEDDED_DEPTH = 8

    /** 1 文書あたりの parseEmbedded 委譲上限件数。 */
    private const val MAX_EMBEDDED_OBJECTS = 64

    /** メタファイルヘッダ探索範囲（偶数オフセット幅）。 */
    private const val METAFILE_HEADER_SEARCH_WINDOW = 256

    /** Contents 画像ヘッダ探索範囲。 */
    private const val IMAGE_HEADER_SEARCH_WINDOW = 512

    /** 単一オブジェクト読み出しバッファサイズ。 */
    private const val READ_BUFFER_SIZE = 8192

    /**
     * オブジェクト枠から埋め込みドキュメントを走査・抽出し、Tika の [extractor] へ委譲する。
     *
     * 本文 XHTML ストリーム ([handler]) のスコープ内で呼び出すことで、抽出されたテキストが
     * 親文書のテキストストリームへ自然にインライン結合される。
     */
    fun extractEmbeddedDocuments(
        data: ByteArray,
        handler: ContentHandler,
        extractor: EmbeddedDocumentExtractor,
        context: ParseContext,
    ) {
        val system = try {
            JtdContainerReader.open(data)
        } catch (e: Exception) {
            // 開封失敗（破損コンテナ等）は握って静かに復帰する
            return
        }
        try {
            system.use { fs ->
                val state = ScanState()
                scanDirectory(fs.root, "/", 1, extractor, context, handler, state)
            }
        } catch (e: Exception) {
            // 走査全体で想定外の例外が起きても本文出力へ伝播させない
        }
    }

    /** 走査状態（委譲回数の通し番号と上限到達フラグ）。 */
    private class ScanState {
        var index = 0
        var limitReached = false
    }

    // ---- 走査ロジック ----

    /**
     * ディレクトリを深さ優先で走査する。
     * 決定論的な結果を保証するため、サブディレクトリは名前の小文字順（lowercase）でソートして処理する。
     */
    private fun scanDirectory(
        dir: DirectoryEntry,
        pathPrefix: String,
        depth: Int,
        extractor: EmbeddedDocumentExtractor,
        context: ParseContext,
        handler: ContentHandler,
        state: ScanState,
    ) {
        if (depth > MAX_EMBEDDED_DEPTH || state.limitReached) return

        val subDirs = mutableListOf<DirectoryEntry>()
        for (entry in dir.entries) {
            if (entry is DirectoryEntry) subDirs.add(entry)
        }
        subDirs.sortBy { it.name.lowercase() }

        for (subDir in subDirs) {
            val subPath = "$pathPrefix${subDir.name}/"
            if (isEmbeddingStorageName(subDir.name)) {
                processEmbeddingStorage(subDir, subPath, extractor, context, handler, state)
            } else {
                scanDirectory(subDir, subPath, depth + 1, extractor, context, handler, state)
            }
        }
    }

    /** オブジェクト枠ストレージ名の判定（trim 後に embedding または oleitem で始まる・大文字小文字無視）。 */
    private fun isEmbeddingStorageName(name: String): Boolean {
        val trimmed = name.trim().lowercase()
        return trimmed.startsWith("embedding") || trimmed.startsWith("oleitem")
    }

    /**
     * オブジェクト枠ストレージ 1 個の処理。
     * 同一ストレージ内では Workbook（表計算）を優先し、続いて Contents（生画像）、最後に EmbeddedPress*（メタファイル）を処理する。
     */
    private fun processEmbeddingStorage(
        storage: DirectoryEntry,
        pathPrefix: String,
        extractor: EmbeddedDocumentExtractor,
        context: ParseContext,
        handler: ContentHandler,
        state: ScanState,
    ) {
        val workbooks = mutableListOf<DocumentEntry>()
        val contents = mutableListOf<DocumentEntry>()
        val presses = mutableListOf<DocumentEntry>()
        for (entry in storage.entries) {
            if (entry !is DocumentEntry) continue
            if (isWorkbookName(entry.name)) {
                workbooks.add(entry)
            } else if (isContentsName(entry.name)) {
                contents.add(entry)
            } else if (isEmbeddedPressName(entry.name)) {
                presses.add(entry)
            }
        }
        contents.sortBy { it.name.lowercase() }
        presses.sortBy { it.name.lowercase() }

        workbooks.forEach { delegateWorkbook(it, pathPrefix, extractor, context, handler, state) }
        contents.forEach { delegateContentsImage(it, pathPrefix, extractor, context, handler, state) }
        presses.forEach { delegatePress(it, pathPrefix, extractor, context, handler, state) }
    }

    /**
     * Workbook ストリーム名の判定。
     * 先頭の制御文字（\u0001〜\u0003等）を除いた名前が "workbook"（大文字小文字無視）なら true。
     */
    private fun isWorkbookName(name: String): Boolean =
        stripLeadingControlChars(name).equals("workbook", ignoreCase = true)

    /**
     * Contents ストリーム名の判定。
     * 先頭の制御文字（\u0001〜\u0003等）を除いた名前が "contents"（大文字小文字無視）なら true。
     */
    private fun isContentsName(name: String): Boolean =
        stripLeadingControlChars(name).equals("contents", ignoreCase = true)

    /**
     * EmbeddedPress ストリーム名の判定。
     * 先頭の制御文字（\u0001〜\u0003等）を除いた接頭辞が "embeddedpress"（大文字小文字無視）なら true。
     */
    private fun isEmbeddedPressName(name: String): Boolean =
        stripLeadingControlChars(name).lowercase().startsWith("embeddedpress")

    /**
     * ストリーム名から先頭の制御文字（0x00〜0x1F）を除去する。
     */
    private fun stripLeadingControlChars(name: String): String {
        var trimmed = name.trim()
        while (trimmed.isNotEmpty() && trimmed[0].code < 0x20) {
            trimmed = trimmed.substring(1)
        }
        return trimmed
    }

    // ---- オブジェクト枠ストリームの前処理と委譲 ----

    /**
     * 表計算オブジェクト枠（Workbook）の委譲。
     *
     * 生 BIFF8（BOF 0x09 0x08）は空の OLE2 CFB の "Workbook" エントリへラップして委譲し、
     * 既に CFB コンテナの場合は無加工で委譲する。
     */
    private fun delegateWorkbook(
        entry: DocumentEntry,
        pathPrefix: String,
        extractor: EmbeddedDocumentExtractor,
        context: ParseContext,
        handler: ContentHandler,
        state: ScanState,
    ) {
        if (state.limitReached) return
        val raw = readEntryBytes(entry) ?: return

        val payload: ByteArray = when {
            raw.size >= 2 && (raw[0].toInt() and 0xFF) == 0x09 && (raw[1].toInt() and 0xFF) == 0x08 ->
                wrapBiffAsCfb(raw) ?: return
            raw.startsWith(CFB_MAGIC) -> raw
            else -> return
        }

        delegate(
            payload = payload,
            handler = handler,
            extractor = extractor,
            context = context,
            state = state,
            contentType = "application/vnd.ms-excel",
            extension = "xls",
            relationshipPath = "$pathPrefix${entry.name}",
        )
    }

    /**
     * Contents ストリームに内包される生画像（JPEG, PNG, BMP, GIF）の委譲。
     *
     * 一太郎の画像枠では、ヘッダおよび元ファイルパスの後に生画像バイナリが直接格納される。
     * マジックバイトを検出してスライスし、Tika の画像・OCR パイプラインへ委譲する。
     */
    private fun delegateContentsImage(
        entry: DocumentEntry,
        pathPrefix: String,
        extractor: EmbeddedDocumentExtractor,
        context: ParseContext,
        handler: ContentHandler,
        state: ScanState,
    ) {
        if (state.limitReached) return
        val raw = readEntryBytes(entry) ?: return
        val sliced = sliceImageFromContents(raw) ?: return

        delegate(
            payload = sliced.bytes,
            handler = handler,
            extractor = extractor,
            context = context,
            state = state,
            contentType = sliced.contentType,
            extension = sliced.extension,
            relationshipPath = "$pathPrefix${entry.name}",
        )
    }

    /** 切り出された画像データ情報。 */
    private data class SlicedImage(
        val bytes: ByteArray,
        val extension: String,
        val contentType: String,
    )

    /**
     * Contents バイナリから生画像をスライスする。
     */
    private fun sliceImageFromContents(raw: ByteArray): SlicedImage? {
        val searchLimit = minOf(raw.size - 4, IMAGE_HEADER_SEARCH_WINDOW)
        for (i in 0..searchLimit) {
            // 1. JPEG: FF D8 FF
            if ((raw[i].toInt() and 0xFF) == 0xFF &&
                (raw[i + 1].toInt() and 0xFF) == 0xD8 &&
                (raw[i + 2].toInt() and 0xFF) == 0xFF
            ) {
                return SlicedImage(raw.copyOfRange(i, raw.size), "jpg", "image/jpeg")
            }
            // 2. PNG: 89 50 4E 47 0D 0A 1A 0A
            if (i + 8 <= raw.size &&
                raw[i] == PNG_MAGIC[0] && raw[i + 1] == PNG_MAGIC[1] &&
                raw[i + 2] == PNG_MAGIC[2] && raw[i + 3] == PNG_MAGIC[3]
            ) {
                return SlicedImage(raw.copyOfRange(i, raw.size), "png", "image/png")
            }
            // 3. GIF: GIF8
            if (raw[i] == GIF_MAGIC[0] && raw[i + 1] == GIF_MAGIC[1] &&
                raw[i + 2] == GIF_MAGIC[2] && raw[i + 3] == GIF_MAGIC[3]
            ) {
                return SlicedImage(raw.copyOfRange(i, raw.size), "gif", "image/gif")
            }
            // 4. BMP: "BM" + DIB ヘッダサイズ検証 (40, 108, 124)
            if (raw[i] == 'B'.code.toByte() && raw[i + 1] == 'M'.code.toByte() && i + 18 <= raw.size) {
                val dibHeaderSize = (raw[i + 14].toInt() and 0xFF) or
                    ((raw[i + 15].toInt() and 0xFF) shl 8) or
                    ((raw[i + 16].toInt() and 0xFF) shl 16) or
                    ((raw[i + 17].toInt() and 0xFF) shl 24)
                if (dibHeaderSize == 40 || dibHeaderSize == 108 || dibHeaderSize == 124) {
                    return SlicedImage(raw.copyOfRange(i, raw.size), "bmp", "image/bmp")
                }
            }
        }
        return null
    }

    /**
     * 図形 press オブジェクト枠（EmbeddedPress）の委譲。
     *
     * 先頭 8 バイトが "METAFILE" のもののみ対象。WMF または EMF ヘッダマーカー
     * を探索し、その位置から末尾までをメタファイル本体として切り出して委譲する。
     */
    private fun delegatePress(
        entry: DocumentEntry,
        pathPrefix: String,
        extractor: EmbeddedDocumentExtractor,
        context: ParseContext,
        handler: ContentHandler,
        state: ScanState,
    ) {
        if (state.limitReached) return
        val raw = readEntryBytes(entry) ?: return
        if (!raw.startsWith(METAFILE_MAGIC)) return
        val metafile = sliceMetafileFromPress(raw) ?: return

        delegate(
            payload = metafile.bytes,
            handler = handler,
            extractor = extractor,
            context = context,
            state = state,
            contentType = metafile.contentType,
            extension = metafile.extension,
            relationshipPath = "$pathPrefix${entry.name}",
        )
    }

    /** 切り出されたメタファイルデータ情報。 */
    private data class SlicedMetafile(
        val bytes: ByteArray,
        val extension: String,
        val contentType: String,
    )

    /**
     * press ストリームから WMF または EMF 本体を切り出す。
     */
    private fun sliceMetafileFromPress(press: ByteArray): SlicedMetafile? {
        val limit = minOf(
            press.size - 4,
            METAFILE_MAGIC.size + METAFILE_HEADER_SEARCH_WINDOW,
        )
        for (offset in METAFILE_MAGIC.size..limit step 2) {
            // 1. WMF: 01 00 09 00
            if (
                press[offset] == WMF_HEADER_MAGIC[0] &&
                press[offset + 1] == WMF_HEADER_MAGIC[1] &&
                press[offset + 2] == WMF_HEADER_MAGIC[2] &&
                press[offset + 3] == WMF_HEADER_MAGIC[3]
            ) {
                return SlicedMetafile(press.copyOfRange(offset, press.size), "wmf", "image/wmf")
            }
            // 2. EMF: 01 00 00 00 かつ offset+40 に " EMF" (0x20 0x45 0x4D 0x46)
            if (
                offset + 44 <= press.size &&
                press[offset] == EMF_HEADER_MAGIC[0] &&
                press[offset + 1] == EMF_HEADER_MAGIC[1] &&
                press[offset + 2] == EMF_HEADER_MAGIC[2] &&
                press[offset + 3] == EMF_HEADER_MAGIC[3] &&
                press[offset + 40] == EMF_SIGNATURE[0] &&
                press[offset + 41] == EMF_SIGNATURE[1] &&
                press[offset + 42] == EMF_SIGNATURE[2] &&
                press[offset + 43] == EMF_SIGNATURE[3]
            ) {
                return SlicedMetafile(press.copyOfRange(offset, press.size), "emf", "image/emf")
            }
        }
        return null
    }

    /**
     * Tika の [EmbeddedDocumentExtractor] への委譲共通処理。
     */
    private fun delegate(
        payload: ByteArray,
        handler: ContentHandler,
        extractor: EmbeddedDocumentExtractor,
        context: ParseContext,
        state: ScanState,
        contentType: String,
        extension: String,
        relationshipPath: String,
    ) {
        val index = state.index + 1
        val metadata = Metadata()
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "embedded-$index.$extension")
        metadata.set(CONTENT_TYPE, contentType)
        metadata.set(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID, relationshipPath)

        if (!extractor.shouldParseEmbedded(metadata, context)) return
        state.index = index
        if (state.index >= MAX_EMBEDDED_OBJECTS) state.limitReached = true

        try {
            TikaInputStream.get(payload).use { stream ->
                extractor.parseEmbedded(stream, handler, metadata, context, false)
            }
        } catch (e: Exception) {
            // 個別オブジェクトのパース失敗は Tika 4 方式でメタデータに記録し、親文書本文の出力は維持する
            EmbeddedDocumentUtil.recordEmbeddedStreamException(e, metadata)
        }
    }

    /**
     * 生 BIFF ストリームを "Workbook" エントリを持つ空の OLE2 CFB にラップする。
     */
    private fun wrapBiffAsCfb(raw: ByteArray): ByteArray? = try {
        val out = ByteArrayOutputStream()
        POIFSFileSystem().use { fs ->
            fs.root.createDocument("Workbook", ByteArrayInputStream(raw))
            fs.writeFilesystem(out)
        }
        out.toByteArray()
    } catch (e: Exception) {
        null
    }

    /** ストリーム読み出し（サイズ上限超過時は null）。 */
    private fun readEntryBytes(entry: DocumentEntry): ByteArray? {
        if (entry.size > JtdContainerReader.MAX_STREAM_BYTES) return null
        return try {
            DocumentInputStream(entry).use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(READ_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > JtdContainerReader.MAX_STREAM_BYTES) {
                        throw IOException("stream exceeds byte limit")
                    }
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        }
    }

    private const val CONTENT_TYPE = "Content-Type"
}
