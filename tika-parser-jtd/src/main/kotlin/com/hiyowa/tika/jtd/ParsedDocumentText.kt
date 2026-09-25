package com.hiyowa.tika.jtd

/**
 * 解析済み DocumentText の保持体。移植元: `document_text.rs` の `ParsedDocumentText`。
 *
 * [plainText] は text 部分（[DocumentTextElement.TextRun] と [DocumentTextElement.InlineText]）の
 * 連結のみを返し、[DocumentTextElement.ControlBoundary]（ページ切れ・制御境界）と
 * [DocumentTextElement.SkippedInlineText]（ルビ注記等）は出力に含まれない。
 *
 * Rust と同一に、最終平文は [DocumentTextParser.trimTrailingExposedControls]
 * （末尾露出制御文字トリム）を適用して返す（Rust `ParsedDocumentText::plain_text`
 * の `trim_trailing_exposed_controls(&output)` と同一位置）。
 * 末尾トリムのみ適用され、文中の制御文字は保持される。
 */
class ParsedDocumentText private constructor(
    private val elements: List<DocumentTextElement>,
) {

    /** 構成要素を返す。`ParsedDocumentText::elements` 相当。 */
    fun elements(): List<DocumentTextElement> = elements

    /** 平文（plain text）を返す。`ParsedDocumentText::plain_text` 相当。 */
    fun plainText(): String {
        val output = StringBuilder()
        for (element in elements) {
            when (element) {
                is DocumentTextElement.TextRun -> output.append(element.text)
                is DocumentTextElement.InlineText -> output.append(element.text)
                is DocumentTextElement.SkippedInlineText -> {}
                is DocumentTextElement.ControlBoundary -> {}
            }
        }
        return DocumentTextParser.trimTrailingExposedControls(output.toString())
    }

    companion object {
        /**
         * 単一 text だけの [ParsedDocumentText] を作る。
         * Rust `ParsedDocumentText::from_text` と同一規則: 空文字列なら要素なし、
         * それ以外は単一の [DocumentTextElement.TextRun]。
         */
        fun fromText(text: String): ParsedDocumentText =
            if (text.isEmpty()) {
                ParsedDocumentText(emptyList())
            } else {
                ParsedDocumentText(listOf(DocumentTextElement.TextRun(text)))
            }

        /** [DocumentTextParser] 用のコンストラクタ（プライベートコンストラクタに委譲）。 */
        internal fun of(elements: List<DocumentTextElement>): ParsedDocumentText =
            ParsedDocumentText(elements)
    }
}
