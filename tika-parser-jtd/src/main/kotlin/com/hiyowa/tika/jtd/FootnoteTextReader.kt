package com.hiyowa.tika.jtd

/**
 * /Footnote ストリームのリーダ。移植元: OpenJTD `rjtd-model/src/lib.rs` の
 * `read_footnote_text`（334〜374 行）とシート単位の脚注読み取り（283〜296 行）。
 *
 * 足注アンカーレコード（cli の streams/footnote テストと同一のバイト列構造）:
 * ```text
 * [0x001c, 0x0001, 0x0007, 0x0000, 0x0000, 0x0001], 0x001d, <ラベル>, 0x001e,
 * 0x0005, 0x0000, 0x0001, 0x001f, <本文>
 * ```
 *
 * core の [DocumentTextParser.parseDocumentText] が InlineText(ラベル, selector 0x0001)
 * と直後の TextRun(本文) に分解することを前提とする。ラベルはイチロが描画する連番
 * 装飾（[1] / (1) / 1) / *1 / ① / [注1] ...）をそのまま含んでいるため、装飾は
 * ハードコードせず構造駆動で扱う。末尾に本文を持たないラベル（内部テンプレート用の
 * sentinel、例: `Note`）は自然に除去される。
 */
object FootnoteTextReader {

    /** 足注アンカーのインラインセレクタ（Rust `FOOTNOTE_ANCHOR_SELECTOR`）。 */
    const val FOOTNOTE_ANCHOR_SELECTOR = 0x0001

    /**
     * /Footnote ストリームから脚注全文を復元する。Rust `read_footnote_text` の完全移植:
     * - [DocumentTextElement.InlineText] かつ selector == [FOOTNOTE_ANCHOR_SELECTOR]
     *   → そのテキストをカレントラベルとして保持
     * - [DocumentTextElement.TextRun] → trim して空ならスキップ、非空なら
     *   カレントラベルがあれば "label text"（ラベル + 半角スペース + 本文）を
     *   要素間を '\n' で区切って追加、ラベルがなければ先頭に半角スペースを付けて追加
     * - 最終トリム後、空なら null
     */
    internal fun readFootnoteText(streamBytes: ByteArray): String? {
        val parsed = DocumentTextParser.parseDocumentText(streamBytes)
        val output = StringBuilder()
        var currentLabel: String? = null

        for (element in parsed.elements()) {
            when (element) {
                is DocumentTextElement.InlineText -> {
                    if (element.selector == FOOTNOTE_ANCHOR_SELECTOR) {
                        currentLabel = element.text
                    }
                }

                is DocumentTextElement.TextRun -> {
                    val text = element.text.trim()
                    if (text.isEmpty()) {
                        continue
                    }
                    val label = currentLabel
                    if (label != null) {
                        currentLabel = null
                        if (output.isNotEmpty()) {
                            output.append('\n')
                        }
                        output.append(label)
                        output.append(' ')
                        output.append(text)
                    } else {
                        output.append(' ')
                        output.append(text)
                    }
                }

                is DocumentTextElement.SkippedInlineText -> {}
                is DocumentTextElement.ControlBoundary -> {}
            }
        }

        val trimmed = output.toString().trim()
        return if (trimmed.isEmpty()) null else trimmed
    }

    /**
     * CFB コンテナ [data] からシートの脚注を復元する（model `parse_with_budget` の
     * シート単位脚注読み取り 283〜286 行相当）。
     *
     * [ObjectSheetsReader.SheetItem.footnotePath]（ルートは /Footnote）を読み、
     * [readFootnoteText] で分解する。ストリーム不在・空結果は null。
     * 読み出し失敗・解析例外は null（従来出力不変のゲート維持）。
     */
    fun readFootnoteForSheet(data: ByteArray, sheet: ObjectSheetsReader.SheetItem): String? {
        return try {
            val stream = JtdContainerReader.withFileSystem(data) { fs ->
                JtdContainerReader.readStream(fs, sheet.footnotePath())
            } ?: return null
            readFootnoteText(stream)
        } catch (e: Exception) {
            null
        }
    }
}
