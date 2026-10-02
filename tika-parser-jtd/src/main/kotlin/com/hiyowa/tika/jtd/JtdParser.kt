package com.hiyowa.tika.jtd

import java.io.ByteArrayOutputStream
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.mime.MediaType
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.Parser
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
 * 4. [extractPayloadBytes]（[DocumentTextParser.readDocumentTextPayload] の 3 経路フォールバック）
 *    で DocumentText をサルベージする（[DocumentTextPayload] を保持）。
 * 5. [ObjectSheetsReader.readDocumentSheets] でシート列を取得する。
 *    - シート 2 以上（マルチシート）→ シート毎 `<div class="sheet">`＋`<h2>{シート名}</h2>`
 *      で区切り、シート本体は流路構造化（[RuleFlowParser] → [RuleFlowAssembler]）が
 *      ブロック列を産出できたら [FlowSaxEmitter]、不能なら平文 `<p>` で emit
 *      （RFC 0013 Phase 3。脚注はシート内末尾 `<p>`、枠注記は sheet 列の後に共用 emit）。
 *      全シートの emit 計画を立ててから emit する（途中失敗なら旧フォールバックへ）。
 *    - シート 1 つ以下で流路構造化がブロック列を産出できた場合 →
 *      [FlowSaxEmitter] で XHTML の `<p>` / `<table>` 構造を emit
 *      （RFC 0013 Phase 2。脚注・枠注記は従来契約のまま末尾に続ける）。
 *    - それ以外（構造化空・失敗）→ フォールバックで [buildCombinedText] の
 *      平文 1 ノード出力（model（rjtd-model `Document::plain_text`）と同一規則:
 *      通常文書なら従来出力+脚注、マルチシートならシート名単独行+シート間 `\n\n` 連結）。
 * 6. [XHTMLContentHandler] 経由で SAX 出力する。
 */
class JtdParser : Parser {

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

        // 4. 形式に応じて DocumentText をサルベージする（[DocumentTextPayload] を保持）。
        val payload = extractPayloadBytes(data, format)

        // P-H+F: /Header のヘッダ本文（本文テキストを出す直前に取得）。
        // ヘッダなし・全空・読取失敗 → null（本文のみの従来出力を維持するゲート）。
        val headerText = readHeaderTextOrNull(data)

        // P2: /LayoutBoxText（囲み枠テキスト）。非空のときのみ
        // 本文の後に「※枠内テキスト」注記を添えて枠テキストを連結する（run_cat 出力契約）。
        // /LayoutBoxText 欠落・抽出空・読取失敗は ""（従来の本文のみ出力を維持する）。
        val boxSuffix = readBoxTextSuffix(data)

        // 5. SAX 出力（XHTML）。
        // RFC 0013 Phase 2/3: シート 2 以上は div.sheet＋h2 によるシート単位構造化、
        // シート 1 つ以下で流路構造化がブロック列を産出できた場合は
        // [FlowSaxEmitter] で <p> / <table> 構造を emit し、不能時は平文フォールバック。
        val sheets = try {
            ObjectSheetsReader.readDocumentSheets(data)
        } catch (e: Exception) {
            // シート情報復元失敗は通常経路（フォールバック出力）に落ちる。
            emptyList()
        }
        val structured: List<FlowBlock>? = try {
            val blocks = RuleFlowAssembler.assemble(RuleFlowParser.parse(payload.bytes))
            if (blocks.isEmpty()) null else blocks
        } catch (e: Exception) {
            // 構造化失敗はフォールバック平文経路に落ちる（出力は従来どおり保つ）。
            null
        }

        val xhtml = XHTMLContentHandler(contentHandler, metadata)
        // XHTMLContentHandler が head 終端で <body> を自動開始・endDocument で自動閉鎖するため
        // body は手で開閉しない（二重 body 多重の防止。v0.3.0 で既存挙動を修正）。
        xhtml.startDocument()
        // 出力契約（Tika 準拠）: ヘッダ行 → 空行 → 本文 → 枠注記（枠テキスト非空のとき）。
        // ヘッダは分岐前から 1 組だけ流す（構造化/フォールバック共通）。
        headerText?.let { xhtml.characters("$it\n\n") }
        when {
            // RFC 0013 Phase 3: マルチシート（2 以上）はシート単位構造化。
            // 全シートの emit 計画を先立てし、途中失敗ならシート emit を一切出さず旧フォールバックへ。
            sheets.size >= 2 -> {
                val plan: List<SheetPlan>? = try {
                    sheets.map { sheet ->
                        val bytes: ByteArray? =
                            if (sheet.storagePath.isEmpty() || sheet.storagePath == "/") {
                                payload.bytes // ルートシート: /DocumentText 本体の既定バイト列
                            } else {
                                JtdContainerReader.withFileSystem(data) { fs ->
                                    JtdContainerReader.readStream(fs, sheet.documentTextPath())
                                }
                            }
                        val blocks: List<FlowBlock>? = if (bytes != null) {
                            RuleFlowAssembler.assemble(RuleFlowParser.parse(bytes)).ifEmpty { null }
                        } else {
                            null
                        }
                        // 構造化不能時の平文代替（構造化経路の <p> 化 emit に流用する）。
                        val flatText: String = when {
                            sheet.storagePath.isEmpty() || sheet.storagePath == "/" -> payload.text
                            bytes == null -> ""
                            else -> try {
                                DocumentTextParser.parseDocumentText(bytes).plainText()
                            } catch (e: Exception) {
                                ""
                            }
                        }
                        val footnote = FootnoteTextReader.readFootnoteForSheet(data, sheet)
                        SheetPlan(sheet.name, blocks, flatText.trim(), footnote?.trim())
                    }.ifEmpty { null }
                } catch (e: Exception) {
                    // シート復元失敗は旧フォールバック（buildCombinedText 平文出力）に落ちる。
                    null
                }

                if (plan != null) {
                    for (p in plan) {
                        // シートは <div class="sheet"> で封じ込め、シート名は <h2>。
                        // 平文が空・ブロックなし・脚注なしのシートも div と h2 は必ず emit。
                        xhtml.startElement("div", "class", "sheet")
                        xhtml.startElement("h2")
                        xhtml.characters(p.name)
                        xhtml.endElement("h2")
                        if (p.blocks != null) {
                            FlowSaxEmitter.emit(p.blocks, xhtml)
                        } else if (p.flat.isNotEmpty()) {
                            xhtml.startElement("p")
                            xhtml.characters(p.flat)
                            xhtml.endElement("p")
                        }
                        if (p.footnote != null && p.footnote.isNotEmpty()) {
                            xhtml.startElement("p")
                            xhtml.characters(p.footnote)
                            xhtml.endElement("p")
                        }
                        xhtml.endElement("div")
                    }
                    // 枠（layout-box）はルート直下 emit 相当として単一シート構造化と同じコードを共用。
                    emitLayoutBox(xhtml, data)
                } else {
                    emitCombinedTextFallback(xhtml, data, payload.text, boxSuffix)
                }
            }

            // シート 1 つ以下の文書で流路構造化がブロック列を産出できた場合:
            // フローブロック列 + 脚注（従来契約: 本文末尾に <p> で連結）+ 枠注記。
            structured != null -> {
                FlowSaxEmitter.emit(structured, xhtml)
                val footnote = FootnoteTextReader.readFootnoteForSheet(
                    data,
                    ObjectSheetsReader.SheetItem(0, "タイトル", "", null),
                )
                if (footnote != null) {
                    xhtml.characters("\n\n")
                    xhtml.startElement("p")
                    xhtml.characters(footnote.trim())
                    xhtml.endElement("p")
                }
                emitLayoutBox(xhtml, data)
            }

            // フォールバック: 従来契約（ヘッダ後の平文 1 ノード + 枠注記）。
            else -> emitCombinedTextFallback(xhtml, data, payload.text, boxSuffix)
        }

        // 6. オブジェクト枠（ObjectBox）の埋め込みドキュメント抽出と委譲
        val parseContext = context ?: ParseContext()
        val extractor = org.apache.tika.extractor.EmbeddedDocumentUtil.getEmbeddedDocumentExtractor(parseContext)
        ObjectBoxExtractor.extractEmbeddedDocuments(
            data = data,
            handler = xhtml,
            extractor = extractor,
            context = parseContext,
        )

        xhtml.endDocument()
    }

    /**
     * /LayoutBoxText（囲み枠）の XHTML 構造 emit（RFC 0013 §10.3・Phase 3 共用）。
     * <div class="layout-box"> 内でブロック毎 <p> に出し、
     * ※注記は -t 互換のためテキストノードとして残す。
     * 欠落・抽出空・読取失敗は emit なし（本文のみの従来出力を維持する）。
     */
    private fun emitLayoutBox(xhtml: XHTMLContentHandler, data: ByteArray) {
        val layoutBlocks: List<String>? = try {
            LayoutBoxTextReader.readLayoutBoxText(data)?.blocks?.filter { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
        if (layoutBlocks == null || layoutBlocks.isEmpty()) return
        xhtml.characters("\n※枠内テキスト\n")
        xhtml.startElement("div", "class", "layout-box")
        for (block in layoutBlocks) {
            xhtml.startElement("p")
            xhtml.characters(block)
            xhtml.endElement("p")
        }
        xhtml.endElement("div")
    }

    /**
     * フォールバック平文出力（従来契約: ヘッダ後の平文 1 ノード + 枠注記）。
     * マルチシート連結・脚注ペアリングは [buildCombinedText] が model
     * （rjtd-model lib.rs 214〜239 / 334〜374 行）と同一規則で組み上げる。
     */
    private fun emitCombinedTextFallback(
        xhtml: XHTMLContentHandler,
        data: ByteArray,
        plainText: String,
        boxSuffix: String,
    ) {
        val combinedText = buildCombinedText(data, plainText)
        if (combinedText.isNotEmpty()) {
            xhtml.characters(combinedText + boxSuffix)
        }
    }

    /**
     * シート構造から本文テキストを組み立てる。
     *
     * 移植元: rjtd-model `Document::plain_text`（lib.rs 214〜239 行）と
     * `IchitaroParser::parse_with_budget`（268〜311 行）。
     * - シートが 1 以下 → 通常本文。単一シートなら脚注を "\n\n"+trim で末尾に連結。
     * - シートが 2 以上 → model の else 分岐を厳密移植:
     *   各シートは「{name}\n{text().trim()}」で、シート間は "\n\n" 連結。
     *   シート毎に脚注があれば "\n\n"+footnote.trim() を追加。
     *   ルートシート（storagePath 空）の既定名は「タイトル」。
     *
     * 脚注は /Footnote（単一・マルチいずれもシート単位）を model 実装に合わせ読む。
     */
    private fun buildCombinedText(
        data: ByteArray,
        plainText: String,
    ): String {
        val sheets = try {
            ObjectSheetsReader.readDocumentSheets(data)
        } catch (e: Exception) {
            // シート情報復元失敗は通常経路（従来出力）に落ちる。
            emptyList()
        }

        return if (sheets.size <= 1) {
            // 通常文書（/DocumentText のみ）。従来出力 plainText + 単一シート脚注連結。
            // model: self.sheets.len() <= 1 → document_plain_text + "\n\n" + fn.trim()
            var text = plainText
            val footnote = FootnoteTextReader.readFootnoteForSheet(
                data,
                ObjectSheetsReader.SheetItem(0, "タイトル", "", null),
            )
            if (footnote != null) {
                text += "\n\n" + footnote.trim()
            }
            text
        } else {
            // マルチシート: model plain_text の else 分岐。
            buildMultiSheetText(data, sheets, plainText)
        }
    }

    /**
     * マルチシート連結（model `plain_text` else 分岐の厳密移植）。
     *
     * 各シートは「{name}\n{text().trim()}」、シート間は "\n\n" 連結、
     * 脚注があれば "\n\n"+footnote.trim() を追加する。
     * シート本体平文: ルート（storagePath 空）は既定の [plainText]（/DocumentText）を再利用、
     * サブシートは [ObjectSheetsReader.SheetItem.documentTextPath] を読み parse。
     *
     * シート本体・脚注は model（lib.rs 276〜286 行）と同一にコンテナ [data] から読む
     * （JustCompressedDocument でも model が内部 CFB に対して read_document_sheets
     * しないことと同一）。ストリーム不在・読取・解析失敗は空
     * （model の `String::new()` / None 落ちに相当）。
     */
    private fun buildMultiSheetText(
        data: ByteArray,
        sheets: List<ObjectSheetsReader.SheetItem>,
        plainText: String,
    ): String {
        val output = StringBuilder()
        sheets.forEachIndexed { index, sheet ->
            if (index > 0) {
                output.append("\n\n")
            }
            val sheetText = if (sheet.storagePath.isEmpty() || sheet.storagePath == "/") {
                plainText // ルートシート: /DocumentText 本体の既定平文
            } else {
                val stream = try {
                    JtdContainerReader.withFileSystem(data) { fs ->
                        JtdContainerReader.readStream(fs, sheet.documentTextPath())
                    }
                } catch (e: Exception) {
                    null
                }
                if (stream != null) {
                    try {
                        DocumentTextParser.parseDocumentText(stream).plainText()
                    } catch (e: Exception) {
                        ""
                    }
                } else {
                    ""
                }
            }
            output.append(sheet.name)
            output.append('\n')
            output.append(sheetText.trim())

            val footnote = FootnoteTextReader.readFootnoteForSheet(data, sheet)
            if (footnote != null) {
                output.append("\n\n")
                output.append(footnote.trim())
            }
        }
        return output.toString()
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
     * [DocumentTextPayload]（生バイト列 + 平文）をそのまま返す（構造化は
     * [JtdParser.parse] が payload.bytes で行う）。
     */
    private fun extractPayloadBytes(data: ByteArray, format: JtdFormat): DocumentTextPayload =
        when (format) {
            // /DocumentText が直接読める通常コンテナ・埋め込み SsmgV.01 コンテナ。
            // 内部的には両経路とも readDocumentTextPayload のフォールバックで処理する。
            JtdFormat.COMPOUND_DOCUMENT_TEXT,
            JtdFormat.COMPOUND_EMBEDDED_DOCUMENT_TEXT ->
                DocumentTextParser.readDocumentTextPayload(data)

            // /JSCompDocument が JustCompressedDocument。展開した内部 CFB を再度開封して読む。
            JtdFormat.COMPOUND_JUST_COMPRESSED_DOCUMENT -> {
                try {
                    DocumentTextParser.readDocumentTextPayload(data)
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

    /**
     * マルチシート構造化の 1 シート分 emit 計画（RFC 0013 Phase 3）。
     *
     * @property name シート名（`<h2>` で emit。root は既定「タイトル」）。
     * @property blocks 流路構造化ブロック列（null のとき [flat] で代替）。
     * @property flat 構造化不能時の平文（trim 済。`<p>` で emit する）。
     * @property footnote シート脚注（`<p>` で emit。null・空は emit なし）。
     */
    private data class SheetPlan(
        val name: String,
        val blocks: List<FlowBlock>?,
        val flat: String,
        val footnote: String?,
    )

    private companion object {
        const val MIME_JTD = "application/vnd.justsystems.ichitaro"
        const val MIME_OLE_STORAGE = "application/x-ole-storage"
        const val MIME_OCTET_STREAM = "application/octet-stream"
        const val KEY_JTD_FORMAT = "X-JTD-Format"
        const val CONTENT_TYPE = "Content-Type"
        const val READ_BUFFER_SIZE = 8192
    }
}
