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
 * 4. [DocumentTextParser.readDocumentTextPayload]（3 経路フォールバック）で DocumentText
 *    をサルベージし平文化する。
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
        val headerText = readHeaderTextOrNull(data)

        // P2: /LayoutBoxText（囲み枠テキスト）。非空のときのみ
        // 本文の後に「※枠内テキスト」注記を添えて枠テキストを連結する（run_cat 出力契約）。
        // /LayoutBoxText 欠落・抽出空・読取失敗は ""（従来の本文のみ出力を維持する）。
        val boxSuffix = readBoxTextSuffix(data)

        // 5. SAX 出力（XHTML）。
        // TODO(Step4): 段落分割・table・ruby の構造化。ここでは平文を一字一句そのまま body に載せる簡易版。
        val xhtml = XHTMLContentHandler(contentHandler, metadata)
        xhtml.startDocument()
        xhtml.startElement("body")
        if (plainText.isNotEmpty()) {
            // 出力契約（Tika 準拠）: ヘッダ行 → 空行 → 本文 → 枠注記（枠テキスト非空のとき）、
            // 1つのテキストノードとして連結。
            xhtml.characters(
                headerText?.let { "$it\n\n" }.orEmpty() + plainText + boxSuffix,
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
     * 枠テキストのサフィックス（P2、Rust `run_cat` の出力契約と同一）。
     * /LayoutBoxText が非空白テキストを復元できた場合のみ "\n※枠内テキスト\n" ＋ 枠テキストを、
     * 欠落・抽出空・読取失敗時は ""（本文のみの従来出力）を返す。
     */
    private fun readBoxTextSuffix(data: ByteArray): String {
        val boxText = LayoutBoxTextReader.readLayoutBoxText(data)?.text() ?: return ""
        return if (boxText.isBlank()) "" else "\n※枠内テキスト\n$boxText"
    }

    /**
     * DocumentText の読み出しは [DocumentTextParser.readDocumentTextPayload] の
     * 3 経路フォールバック（/DocumentText → /JSCompDocument 展開 → 埋め込みスキャン）に
     * 全経路で委譲し、形式ごとの重複読み出しロジックを排除する。
     */
    private fun extractPlainText(data: ByteArray, format: JtdFormat): String = when (format) {
        // /DocumentText が直接読める通常コンテナ・埋め込み SsmgV.01 コンテナ。
        // 内部的には両経路とも readDocumentTextPayload のフォールバックで処理する。
        JtdFormat.COMPOUND_DOCUMENT_TEXT,
        JtdFormat.COMPOUND_EMBEDDED_DOCUMENT_TEXT ->
            DocumentTextParser.readDocumentTextPayload(data).text

        // /JSCompDocument が JustCompressedDocument。展開した内部 CFB を再度開封して読む。
        JtdFormat.COMPOUND_JUST_COMPRESSED_DOCUMENT -> {
            try {
                DocumentTextParser.readDocumentTextPayload(data).text
            } catch (e: NotFoundException) {
                // /DocumentText 欠落系は従来どおり NotFoundException のまま伝播。
                throw e
            } catch (e: JtdException) {
                // -lh5- 以外の LHA メソッド・破損等 → 未対応形式として変換（Step3 の挙動維持）。
                throw UnsupportedFormatException("JustCompressedDocument decompress failed: ${e.message}")
            }
        }

        // CFB ではあるが既知レイアウトに一致しない。
        JtdFormat.COMPOUND_UNKNOWN ->
            throw UnsupportedFormatException("unrecognized JTD compound layout")

        // 防御的: この分岐に到達しない（parse が先に弾く）。
        JtdFormat.UNKNOWN ->
            throw UnsupportedFormatException("not a JTD document (no CFB magic)")
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
        const val READ_BUFFER_SIZE = 8192
    }
}
