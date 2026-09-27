package com.hiyowa.tika.jtd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import org.apache.poi.hssf.usermodel.HSSFWorkbook
import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.DocumentEntry
import org.apache.poi.poifs.filesystem.DocumentInputStream
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.tika.extractor.EmbeddedDocumentExtractor
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.Parser
import org.apache.tika.sax.BodyContentHandler
import org.xml.sax.ContentHandler
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 埋め込み OLE オブジェクト再帰抽出（`ObjectBoxExtractor` 連携）のテスト。
 *
 * 調査レポート §6/§8 の設計に基づく:
 * - `Embedding N` / `OleItem N` ストレージ内の `Workbook`（生 BIFF ストリーム・先頭 BOF 0x0809）を
 *   (delegate 前に CFB の "Workbook" エントリへラップし) `embedded-N.xls` として、
 *   `shouldParseEmbedded` → `parseEmbedded` に委譲する。
 * - `EmbeddedPress`（先頭 8 バイト "METAFILE" の図形 press）は WMF ヘッダ
 *   （type=0x0001・headerSize=9）位置から切り出し `embedded-N.wmf` として委譲する。
 * - 委譲は本文 XHTML ストリーム内にインライン結合され、`outputHtml=false` を渡す。
 *
 * 本文・埋め込み内容には宮沢賢治「銀河鉄道の夜」（青空文庫・パブリックドメイン）を使用する。
 */
class ObjectBoxRecursionTest {

    companion object {
        // 青空文庫「銀河鉄道の夜」から引用
        private const val ROOT_TEXT = "ジョバンニは勢よく立ちあがりましたが、立って見るともうはっきりとそれを答えることができないのでした。"
        private const val EMBED_CELL_1 = "大きな望遠鏡で銀河をよっく調べると銀河は大体何でしょう。"
        private const val EMBED_CELL_2 = "やっぱり星だとジョバンニは思いましたがこんどもすぐに答えることができませんでした。"
        private const val EMBED_CELL_3 = "カムパネルラが手をあげました。"
        private const val EMBED_CELL_4 = "「ああきっと一緒だよ。お母さん、窓をしめて置こうか。」"

        // 図形 press のプレフィックスと WMF ヘッダ境界（実測レイアウト準拠）
        private val METAFILE_MAGIC = "METAFILE".toByteArray(Charsets.ISO_8859_1)
        private val PRESS_FILLER = ByteArray(38)
        private val WMF_HEADER = byteArrayOf(
            0x01, 0x00, 0x09, 0x00,
            0x00, 0x03, 0x11, 0x22,
            0x00, 0x00, 0x33, 0x00,
            0x44, 0x00, 0x00, 0x00,
            0x00, 0x00,
        )

        private const val EMBEDDED_PRESS_NAME = "\u0003EmbeddedPress"

        // EMF ヘッダ（iType=1 + 36バイトfiller + " EMF"）
        private val EMF_HEADER = byteArrayOf(
            0x01, 0x00, 0x00, 0x00,
        ) + ByteArray(36) + " EMF".toByteArray(Charsets.ISO_8859_1)

        // 画像ヘッダ（実測レイアウト準拠の最小マジック）
        private val JPEG_HEADER = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
        private val PNG_HEADER = byteArrayOf(
            0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
            0x0D, 0x0A, 0x1A, 0x0A,
        )
        private val BMP_HEADER = byteArrayOf(
            'B'.code.toByte(), 'M'.code.toByte(),
            0x00, 0x00, 0x00, 0x00, // file size
            0x00, 0x00, 0x00, 0x00, // reserved
            0x36, 0x00, 0x00, 0x00, // pixel data offset
            0x28, 0x00, 0x00, 0x00, // DIB header size (40 = BITMAPINFOHEADER)
        )
    }

    // ---- テストハーネス ----

    /**
     * parseEmbedded の委譲を記録するモック抽出器。
     * [allow]=false で shouldParseEmbedded 拒否時、[failWith] で parseEmbedded 例外時の挙動を検証する。
     */
    private class RecordingExtractor(
        private val allow: Boolean = true,
        private val failWith: Exception? = null,
    ) : EmbeddedDocumentExtractor {

        data class Call(
            val resourceName: String?,
            val contentType: String?,
            val relationshipId: String?,
            val bytes: ByteArray,
            val outputHtml: Boolean,
        )

        val calls = mutableListOf<Call>()
        val consultedNames = mutableListOf<String?>()

        override fun shouldParseEmbedded(metadata: Metadata, context: ParseContext): Boolean {
            consultedNames.add(metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY))
            return allow
        }

        override fun parseEmbedded(
            stream: TikaInputStream,
            handler: ContentHandler,
            metadata: Metadata,
            context: ParseContext,
            outputHtml: Boolean,
        ) {
            if (failWith != null) throw failWith
            val name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY)
            calls.add(
                Call(
                    resourceName = name,
                    contentType = metadata.get("Content-Type"),
                    relationshipId = metadata.get(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID),
                    bytes = stream.readBytes(),
                    outputHtml = outputHtml,
                ),
            )
            // 委譲先が本文ストリームへインライン結合する契約: テキストをそのまま流す
            val inline = "【埋め込み:$name】".toCharArray()
            handler.characters(inline, 0, inline.size)
        }
    }

    private fun markerDocumentText(text: String): ByteArray {
        val bytes = mutableListOf<Byte>()
        bytes.addAll("SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        bytes.add(0x00)
        bytes.add(0x1f)
        bytes.addAll(text.toByteArray(Charsets.UTF_16BE).toList())
        return bytes.toByteArray()
    }

    /** 埋め込み用的小型 XLS を生成する（POI HSSF → CFB ラッパから生 BIFF8 ストリームを抽出）。 */
    private fun rawBiffWorkbook(sheetName: String, cellText: String): ByteArray {
        val wrapped = ByteArrayOutputStream()
        HSSFWorkbook().use { wb ->
            val sheet = wb.createSheet(sheetName)
            sheet.createRow(0).createCell(0).setCellValue(cellText)
            wb.write(wrapped)
        }
        // HSSF の write() は BIFF8 を CFB の "Workbook" に格納するため、その本体を生ストリームとして取り出す
        POIFSFileSystem(ByteArrayInputStream(wrapped.toByteArray())).use { fs ->
            for (entry in fs.root.entries) {
                if (entry is DocumentEntry && entry.name.equals("Workbook", ignoreCase = true)) {
                    return DocumentInputStream(entry).use { it.readBytes() }
                }
            }
            error("生成した XLS に Workbook ストリームが見つからない")
        }
    }

    /** "METAFILE" プレフィックス + 38 バイトの press ヘッダ + WMF データ を模したストリーム。 */
    private fun metafilePressStream(wmfBody: ByteArray): ByteArray {
        return METAFILE_MAGIC + PRESS_FILLER + WMF_HEADER + wmfBody
    }

    /** "METAFILE" プレフィックス + 38 バイトの press ヘッダ + EMF データ を模したストリーム。 */
    private fun metafileEmfPressStream(emfBody: ByteArray): ByteArray {
        return METAFILE_MAGIC + PRESS_FILLER + EMF_HEADER + emfBody
    }

    /** 任意バイトのヘッダ／パス文字列プレフィックス + 生画像データを模した Contents ストリーム。 */
    private fun contentsStream(prefixLength: Int, imagePayload: ByteArray): ByteArray {
        return ByteArray(prefixLength) { 0x30.toByte() } + imagePayload
    }

    private fun cfb(entries: Map<String, ByteArray>): ByteArray {
        POIFSFileSystem().use { fs ->
            for ((path, payload) in entries) {
                val parts = path.trimStart('/').split('/').filter { it.isNotEmpty() }
                var dir: DirectoryEntry = fs.root
                for (i in 0 until parts.size - 1) {
                    dir = try {
                        dir.getEntry(parts[i]) as DirectoryEntry
                    } catch (e: FileNotFoundException) {
                        dir.createDirectory(parts[i])
                    } catch (e: IllegalArgumentException) {
                        dir.createDirectory(parts[i])
                    }
                }
                dir.createDocument(parts.last(), ByteArrayInputStream(payload))
            }
            val out = ByteArrayOutputStream()
            fs.writeFilesystem(out)
            return out.toByteArray()
        }
    }

    private fun parse(data: ByteArray, context: ParseContext): Pair<String, Metadata> {
        val metadata = Metadata()
        val handler = BodyContentHandler(-1)
        TikaInputStream.get(ByteArrayInputStream(data)).use { stream ->
            JtdParser().parse(stream, handler, metadata, context)
        }
        return handler.toString() to metadata
    }

    /**
     * テスト用 Excel パーサー。
     * tika-core 単体環境下でも ParsingEmbeddedDocumentExtractor の再帰先として
     * HSSF セルテキストを BodyContentHandler に書き出す。
     */
    private class TestExcelParser : Parser {
        override fun getSupportedTypes(context: ParseContext?): Set<org.apache.tika.mime.MediaType> =
            setOf(org.apache.tika.mime.MediaType.parse("application/vnd.ms-excel")!!)

        override fun parse(
            stream: TikaInputStream,
            handler: ContentHandler,
            metadata: Metadata,
            context: ParseContext?,
        ) {
            val xhtml = org.apache.tika.sax.XHTMLContentHandler(handler, metadata)
            xhtml.startDocument()
            POIFSFileSystem(stream).use { fs ->
                HSSFWorkbook(fs).use { wb ->
                    val text = wb.getSheetAt(0).getRow(0).getCell(0).stringCellValue
                    xhtml.element("p", text)
                }
            }
            xhtml.endDocument()
        }
    }

    private fun contextWith(extractor: EmbeddedDocumentExtractor?): ParseContext {
        val context = ParseContext()
        if (extractor != null) {
            context.set(EmbeddedDocumentExtractor::class.java, extractor)
        }
        context.set(Parser::class.java, TestExcelParser())
        return context
    }

    // ---- Workbook（埋め込み表計算）の委譲 ----

    @Test
    fun delegatesWorkbookStorageToExtractorWithMetadataContract() {
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                "/EmbedItems/Embedding 1/Workbook" to rawBiffWorkbook("銀河", EMBED_CELL_1),
            ),
        )
        val extractor = RecordingExtractor()

        val (text, _) = parse(data, contextWith(extractor))

        assertEquals(listOf<String?>("embedded-1.xls"), extractor.consultedNames)
        assertEquals(1, extractor.calls.size)
        val call = extractor.calls[0]
        assertEquals("embedded-1.xls", call.resourceName)
        assertEquals("application/vnd.ms-excel", call.contentType)
        assertEquals("/EmbedItems/Embedding 1/Workbook", call.relationshipId)
        assertEquals(false, call.outputHtml)
        // delegate 前に CFB の "Workbook" へラップされたバイト列が委譲される（先頭 OLE2 マジック）
        assertEquals(0xD0, call.bytes[0].toInt() and 0xFF)
        assertEquals(0x11, call.bytes[2].toInt() and 0xFF)
        assertEquals(0xE0, call.bytes[3].toInt() and 0xFF)
        assertContains(text, ROOT_TEXT)
        assertContains(text, "【埋め込み:embedded-1.xls】")
    }

    @Test
    fun delegatesMultipleEmbeddingsInNameOrderWithUniqueIndices() {
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                "/EmbedItems/Embedding 1/Workbook" to rawBiffWorkbook("つうしん1", EMBED_CELL_1),
                "/EmbedItems/Embedding 2/Workbook" to rawBiffWorkbook("つうしん2", EMBED_CELL_2),
            ),
        )
        val extractor = RecordingExtractor()

        parse(data, contextWith(extractor))

        assertEquals(
            listOf("/EmbedItems/Embedding 1/Workbook", "/EmbedItems/Embedding 2/Workbook"),
            extractor.calls.map { it.relationshipId },
            "Embedding は名前順に決定論的へ走査し、通し番号を付与しなければならない",
        )
        assertEquals(listOf("embedded-1.xls", "embedded-2.xls"), extractor.calls.map { it.resourceName })
    }

    @Test
    fun delegatesOleItemStorageUnderObjectSheets() {
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                "/ObjectSheets/OleSheet/OleItem 1/Workbook" to rawBiffWorkbook("けいこ", EMBED_CELL_3),
            ),
        )
        val extractor = RecordingExtractor()

        parse(data, contextWith(extractor))

        assertEquals(1, extractor.calls.size)
        assertEquals("/ObjectSheets/OleSheet/OleItem 1/Workbook", extractor.calls[0].relationshipId)
        assertEquals("embedded-1.xls", extractor.calls[0].resourceName)
    }

    @Test
    fun extractsEmbeddedWorkbookTextEndToEnd() {
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                "/EmbedItems/Embedding 1/Workbook" to rawBiffWorkbook("銀河", EMBED_CELL_4),
            ),
        )

        // モックなし: デフォルトの ParsingEmbeddedDocumentExtractor + AutoDetectParser 経路
        val (text, _) = parse(data, contextWith(null))

        assertContains(text, ROOT_TEXT)
        assertTrue(
            text.contains(EMBED_CELL_4),
            "埋め込み表計算のセルテキストが本文ストリームに復元されていない",
        )
    }

    // ---- EmbeddedPress（METAFILE 図形 press）の委譲 ----

    @Test
    fun delegatesMetafilePressStreamSlicedFromWmfHeader() {
        val wmfBody = "galaxy station".toByteArray(Charsets.ISO_8859_1)
        val press = metafilePressStream(wmfBody)
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                "/EmbedItems/Embedding 1/$EMBEDDED_PRESS_NAME" to press,
            ),
        )
        val extractor = RecordingExtractor()

        parse(data, contextWith(extractor))

        assertEquals(1, extractor.calls.size)
        val call = extractor.calls[0]
        assertEquals("embedded-1.wmf", call.resourceName)
        assertEquals("image/wmf", call.contentType)
        assertEquals("/EmbedItems/Embedding 1/$EMBEDDED_PRESS_NAME", call.relationshipId)
        assertEquals(false, call.outputHtml)
        // "METAFILE"+filler を剥がし WMF ヘッダ（01 00 09 00）から切り出したバイト列であること
        val expected = WMF_HEADER + wmfBody
        assertTrue(
            call.bytes.contentEquals(expected),
            "press ストリームは WMF ヘッダ位置から正確に切り出さなければならない",
        )
    }

    @Test
    fun delegatesEmbeddedPress2AndWorkbooksTogetherInStableOrder() {
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                "/EmbedItems/Embedding 1/Workbook" to rawBiffWorkbook("銀河", EMBED_CELL_1),
                "/EmbedItems/Embedding 1/\u0003EmbeddedPress2" to metafilePressStream("galaxy chart".toByteArray(Charsets.ISO_8859_1)),
            ),
        )
        val extractor = RecordingExtractor()

        parse(data, contextWith(extractor))

        assertEquals(
            listOf("embedded-1.xls", "embedded-2.wmf"),
            extractor.calls.map { it.resourceName },
            "同一ストレージ内は Workbook 優先・EmbeddedPress* は名前順で走査する",
        )
    }

    @Test
    fun delegatesMetafilePressStreamSlicedFromEmfHeader() {
        val emfBody = "galaxy emf record".toByteArray(Charsets.ISO_8859_1)
        val press = metafileEmfPressStream(emfBody)
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                "/EmbedItems/Embedding 1/$EMBEDDED_PRESS_NAME" to press,
            ),
        )
        val extractor = RecordingExtractor()

        parse(data, contextWith(extractor))

        assertEquals(1, extractor.calls.size)
        val call = extractor.calls[0]
        assertEquals("embedded-1.emf", call.resourceName)
        assertEquals("image/emf", call.contentType)
        assertEquals("/EmbedItems/Embedding 1/$EMBEDDED_PRESS_NAME", call.relationshipId)
        val expected = EMF_HEADER + emfBody
        assertTrue(
            call.bytes.contentEquals(expected),
            "press ストリームは EMF ヘッダ位置から正確に切り出さなければならない",
        )
    }

    // ---- Contents（埋め込み生画像 JPEG, PNG, BMP）の委譲 ----

    @Test
    fun delegatesContentsJpegStreamSlicedFromHeader() {
        val jpegBody = "JFIF raw image data".toByteArray(Charsets.ISO_8859_1)
        val contents = contentsStream(96, JPEG_HEADER + jpegBody)
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                "/EmbedItems/Embedding 1/Contents" to contents,
            ),
        )
        val extractor = RecordingExtractor()

        parse(data, contextWith(extractor))

        assertEquals(1, extractor.calls.size)
        val call = extractor.calls[0]
        assertEquals("embedded-1.jpg", call.resourceName)
        assertEquals("image/jpeg", call.contentType)
        assertEquals("/EmbedItems/Embedding 1/Contents", call.relationshipId)
        val expected = JPEG_HEADER + jpegBody
        assertTrue(call.bytes.contentEquals(expected), "Contents から JPEG ヘッダ以降が切り出されること")
    }

    @Test
    fun delegatesContentsPngStreamSlicedFromHeader() {
        val pngBody = "IHDR PNG chunk data".toByteArray(Charsets.ISO_8859_1)
        val contents = contentsStream(64, PNG_HEADER + pngBody)
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                "/EmbedItems/Embedding 1/Contents" to contents,
            ),
        )
        val extractor = RecordingExtractor()

        parse(data, contextWith(extractor))

        assertEquals(1, extractor.calls.size)
        val call = extractor.calls[0]
        assertEquals("embedded-1.png", call.resourceName)
        assertEquals("image/png", call.contentType)
        val expected = PNG_HEADER + pngBody
        assertTrue(call.bytes.contentEquals(expected), "Contents から PNG ヘッダ以降が切り出されること")
    }

    @Test
    fun delegatesContentsBmpStreamSlicedFromHeader() {
        val bmpBody = "BMP raster pixel bytes".toByteArray(Charsets.ISO_8859_1)
        val contents = contentsStream(128, BMP_HEADER + bmpBody)
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                "/EmbedItems/Embedding 1/Contents" to contents,
            ),
        )
        val extractor = RecordingExtractor()

        parse(data, contextWith(extractor))

        assertEquals(1, extractor.calls.size)
        val call = extractor.calls[0]
        assertEquals("embedded-1.bmp", call.resourceName)
        assertEquals("image/bmp", call.contentType)
        val expected = BMP_HEADER + bmpBody
        assertTrue(call.bytes.contentEquals(expected), "Contents から BMP ヘッダ以降が切り出されること")
    }

    @Test
    fun delegatesWorkbookContentsAndPressInPriorityOrder() {
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                "/EmbedItems/Embedding 1/Workbook" to rawBiffWorkbook("表", EMBED_CELL_1),
                "/EmbedItems/Embedding 1/Contents" to contentsStream(64, JPEG_HEADER + "jpeg".toByteArray()),
                "/EmbedItems/Embedding 1/$EMBEDDED_PRESS_NAME" to metafilePressStream("wmf".toByteArray()),
            ),
        )
        val extractor = RecordingExtractor()

        parse(data, contextWith(extractor))

        assertEquals(
            listOf("embedded-1.xls", "embedded-2.jpg", "embedded-3.wmf"),
            extractor.calls.map { it.resourceName },
            "同一ストレージ内は Workbook → Contents → EmbeddedPress の順序で委譲されること",
        )
    }

    @Test
    fun skipsPressStreamWithoutMetafileMagic() {
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                "/EmbedItems/Embedding 1/$EMBEDDED_PRESS_NAME" to "NOTAMETAFILE銀河".toByteArray(Charsets.ISO_8859_1),
            ),
        )
        val extractor = RecordingExtractor()

        val (text, _) = parse(data, contextWith(extractor))

        assertEquals(0, extractor.calls.size, "METAFILE マジックなしの press は委譲してはならない")
        assertContains(text, ROOT_TEXT)
    }

    // ---- ゲート・耐性 ----

    @Test
    fun doesNotDelegateWhenShouldParseEmbeddedRefuses() {
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                "/EmbedItems/Embedding 1/Workbook" to rawBiffWorkbook("銀河", EMBED_CELL_1),
            ),
        )
        val extractor = RecordingExtractor(allow = false)

        val (text, _) = parse(data, contextWith(extractor))

        assertEquals(1, extractor.consultedNames.size)
        assertEquals(0, extractor.calls.size)
        assertEquals(ROOT_TEXT, text.trim(), "拒否時は従来出力と完全一致しなければならない")
    }

    @Test
    fun parseContinuesWhenEmbeddedParseThrows() {
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                "/EmbedItems/Embedding 1/Workbook" to rawBiffWorkbook("銀河", EMBED_CELL_1),
            ),
        )
        val extractor = RecordingExtractor(failWith = IOException("embedded boom"))

        val (text, _) = parse(data, contextWith(extractor))

        assertEquals(ROOT_TEXT, text.trim(), "埋め込み解析失敗は本文出力へ持ち込まない")
    }

    @Test
    fun documentWithoutEmbeddedObjectsOutputUnchanged() {
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                "/Header" to markerDocumentText("今夜"),
                "/EmbedItems/EmbeddingInfo" to byteArrayOf(0x00, 0x01, 0x02),
            ),
        )
        val extractor = RecordingExtractor()

        val (text, _) = parse(data, contextWith(extractor))

        assertEquals(0, extractor.calls.size, "Embedding/OleItem 以外のエントリは委譲対象外")
        assertContains(text, ROOT_TEXT)
    }

    @Test
    fun stopsRecursionAtDepthLimit() {
        val deep = "/a/b/c/d/e/f/Embedding 1/Workbook"
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                deep to rawBiffWorkbook("銀河", EMBED_CELL_3),
            ),
        )
        val extractor = RecordingExtractor()

        val (text, _) = parse(data, contextWith(extractor))

        assertEquals(0, extractor.calls.size, "深さ上限を越えた埋め込みストレージは走査しない")
        assertContains(text, ROOT_TEXT)
    }

    // ---- デフォルト抽出器の解決 ----

    @Test
    fun parseWithoutContextExtractorStillUsesDefaultExtractor() {
        val data = cfb(
            mapOf(
                "/DocumentText" to markerDocumentText(ROOT_TEXT),
                "/EmbedItems/Embedding 1/Workbook" to rawBiffWorkbook("銀河", EMBED_CELL_4),
            ),
        )

        // ParseContext に extractor を注入しない → デフォルトの ParsingEmbeddedDocumentExtractor で再帰する
        val metadata = Metadata()
        val handler = BodyContentHandler(-1)
        val context = ParseContext().apply { set(Parser::class.java, TestExcelParser()) }
        TikaInputStream.get(ByteArrayInputStream(data)).use { stream ->
            JtdParser().parse(stream, handler, metadata, context)
        }

        assertContains(handler.toString(), EMBED_CELL_4)
        // extractor 未注入でも parse は完走する（ParsingEmbeddedDocumentExtractor を解決できること）
        assertTrue(
            ParsingEmbeddedDocumentExtractor().shouldParseEmbedded(Metadata(), context),
        )
    }
}
