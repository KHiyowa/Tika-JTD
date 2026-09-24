package com.hiyowa.tika.jtd

import java.io.ByteArrayOutputStream
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.mime.MediaType
import org.apache.tika.parser.AbstractParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.XHTMLContentHandler
import org.xml.sax.ContentHandler

/**
 * Tika 統合ポイント（`Parser` 実装）。移行レポート 第 6 節の契約に従い、
 * `AutoDetectParser` が ServiceLoader 経由で本パーサーを辿れるようにする入口クラス。
 *
 * パース手順（Tika 4 の `parse` シグネチャは `TikaInputStream` ベース）:
 * 1. [TikaInputStream] から全バイトを読む（入力上限 [ParseLimits.DEFAULT.maxInputBytes]、
 *    超過は [ResourceLimitException]）。
 * 2. [JtdFormatDetector.detect] で形式を判定し、必須 metadata を設定
 *    （`X-JTD-Format` と `Content-Type`）。
 * 3. [JtdFormat.UNKNOWN] は [UnsupportedFormatException] をスロー。
 * 4. 形式に応じて `/DocumentText` をサルベージし [DocumentTextParser] で平文化する。
 * 5. [XHTMLContentHandler] 経由で SAX 出力する。
 *
 * ※ 段落分割・table・ruby の構造化は Step4 以降（[TODO] コメント参照）。
 */
class JtdParser : AbstractParser() {

    override fun getSupportedTypes(context: ParseContext?): Set<MediaType> =
        mutableSetOf(
            MediaType.parse(MIME_JTD)!!,
            MediaType.parse(MIME_OLE_STORAGE)!!,
            MediaType.parse(MIME_OCTET_STREAM)!!,
        )

    override fun parse(
        stream: TikaInputStream,
        contentHandler: ContentHandler,
        metadata: Metadata,
        context: ParseContext?,
    ) {
        // 1. 全バイトを読む（graffiti 防止のため読み込み中に上限を監視）。
        val data = readWithInputLimit(stream, ParseLimits.DEFAULT.maxInputBytes)

        // 2. 形式判定 + 必須 metadata（Content-Type は常に自前 MIME で上書き設定）。
        val format = JtdFormatDetector.detect(data)
        metadata.set(KEY_JTD_FORMAT, format.asString)
        metadata.set(CONTENT_TYPE, MIME_JTD)

        // 3. CFB でもない未知データは未対応。
        if (format == JtdFormat.UNKNOWN) {
            throw UnsupportedFormatException("not a JTD document (no CFB magic)")
        }

        // 4. 形式に応じて平文をサルベージする。
        val plainText = extractPlainText(data, format)

        // P-H+F: /Header のヘッダ本文（本文テキストを出す直前に取得）。
        // ヘッダなし・全空・読取失敗 → null（本文のみの従来出力を維持するゲート）。
        // TODO(Step4b): LayoutBoxText の後付け連結は次ステップ。
        val headerText = readHeaderTextOrNull(data)

        // 5. SAX 出力（XHTML）。
        // TODO(Step4): 段落分割・table・ruby の構造化。ここでは平文を一字一句そのまま body に載せる簡易版。
        val xhtml = XHTMLContentHandler(contentHandler, metadata)
        xhtml.startDocument()
        xhtml.startElement("body")
        if (plainText.isNotEmpty()) {
            // 出力契約（Tika 準拠）: ヘッダ行 → 空行 → 本文（1つのテキストノードとして連結）。
            xhtml.characters(
                if (headerText != null) "$headerText\n\n$plainText" else plainText,
            )
        }
        xhtml.endElement("body")
        xhtml.endDocument()
    }

    /**
     * /Header の復元テキスト全体（行を改行で結合）を返す。
     * 欠落・レイアウト不符・読取失敗はすべて null（本文のみの従来出力ゲート。
     * ここではいかなる例外も出力チェーンに持ち込まない）。
     */
    private fun readHeaderTextOrNull(data: ByteArray): String? =
        try {
            HeaderTextReader.readHeaderText(data)?.text()
        } catch (e: Exception) {
            null
        }

    /**
     * 形式ごとに DocumentText を読み出して平文化する。
     */
    private fun extractPlainText(data: ByteArray, format: JtdFormat): String = when (format) {
        // /DocumentText が直接読める通常コンテナ。
        JtdFormat.COMPOUND_DOCUMENT_TEXT -> {
            val docText = readDocumentText(data)
            DocumentTextParser.parseDocumentText(docText).plainText()
        }

        // /JSCompDocument が JustCompressedDocument。展開した内部 CFB を再度開封して読む。
        JtdFormat.COMPOUND_JUST_COMPRESSED_DOCUMENT -> {
            val jsComp = JtdContainerReader.withFileSystem(data) { fs ->
                JtdContainerReader.readStream(fs, PATH_JS_COMP)
                    ?: throw NotFoundException("missing /JSCompDocument")
            }
            val inner = try {
                // Step3 のコンポーネント（LHA -lh5-）で展開する。
                JustCompressedDocument.decompressJustCompressedDocument(jsComp)
            } catch (e: JtdException) {
                // -lh5- 以外の LHA メソッド・破損等 → 未対応形式として変換。
                throw UnsupportedFormatException("JustCompressedDocument decompress failed: ${e.message}")
            }
            val docText = readDocumentText(inner)
            DocumentTextParser.parseDocumentText(docText).plainText()
        }

        // 任意ストリーム内に SsmgV.01 が埋め込まれているコンテナ。
        JtdFormat.COMPOUND_EMBEDDED_DOCUMENT_TEXT -> {
            // TODO(Step4): 完全な埋め込みスキャン（尤度フィルタ）。
            // 簡易版: SsmgV.01 マーカーの出現位置以降を DocumentText として通す（末尾詰みは parser が無視する）。
            val offset = data.indexOfWindow(SSMG_MARKER)
                ?: throw NotFoundException("embedded DocumentText (SsmgV.01) not found")
            DocumentTextParser.parseDocumentText(data.copyOfRange(offset, data.size)).plainText()
        }

        // CFB ではあるが既知レイアウトに一致しない。
        JtdFormat.COMPOUND_UNKNOWN ->
            throw UnsupportedFormatException("unrecognized JTD compound layout")

        // 防御的: この分岐に到達しない（parse が先に弾く）。
        JtdFormat.UNKNOWN ->
            throw UnsupportedFormatException("not a JTD document (no CFB magic)")
    }

    /**
     * CFB コンテナ [data] から `/DocumentText` ストリームを読み出す。
     */
    private fun readDocumentText(data: ByteArray): ByteArray =
        JtdContainerReader.withFileSystem(data) { fs ->
            JtdContainerReader.readStream(fs, PATH_DOCUMENT_TEXT)
                ?: throw NotFoundException("missing /DocumentText")
        }

    /**
     * [TikaInputStream] から全バイトを読み出す。読み込み中に [limit] を超えたら
     * [ResourceLimitException] をスローする（無制限確保を防ぐ graffiti 防止）。
     */
    private fun readWithInputLimit(stream: TikaInputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val n = stream.read(buffer)
            if (n == -1) break
            total += n
            if (total > limit) {
                throw ResourceLimitException("input bytes", limit, total)
            }
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private companion object {
        const val MIME_JTD = "application/vnd.justsystem.ichitaro"
        const val MIME_OLE_STORAGE = "application/x-ole-storage"
        const val MIME_OCTET_STREAM = "application/octet-stream"
        const val KEY_JTD_FORMAT = "X-JTD-Format"
        const val CONTENT_TYPE = "Content-Type"
        const val PATH_DOCUMENT_TEXT = "/DocumentText"
        const val PATH_JS_COMP = "/JSCompDocument"
        const val READ_BUFFER_SIZE = 8192
        val SSMG_MARKER: ByteArray = "SsmgV.01".toByteArray(Charsets.ISO_8859_1)
    }
}

/**
 * [haystack] 中の [needle] の最初の出現位置（Rust の windows(n).position 相当）。
 * 見つけられなければ null。
 */
private fun ByteArray.indexOfWindow(needle: ByteArray): Int? {
    if (needle.isEmpty()) return 0
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return null
}
