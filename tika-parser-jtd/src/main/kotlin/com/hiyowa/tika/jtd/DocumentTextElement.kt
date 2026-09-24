package com.hiyowa.tika.jtd

/**
 * DocumentText の構成要素。移植元: `document_text.rs` の `DocumentTextElement` enum。
 *
 * `SkippedInlineText.selector` は Rust の `SkippedInlineTextSegment::selector()` と同様に、
 * 直前コンテキストの末尾（= セレクタ自体）を表す [Int]（未定なら null）。
 */
sealed interface DocumentTextElement {

    /** プレインテキスト run（0x001f マーカーで開始）。 */
    data class TextRun(val text: String) : DocumentTextElement

    /** インラインテキスト区間（0x001d で開始、0x001e で終端）。[selector] はセレクタコード。 */
    data class InlineText(val selector: Int?, val text: String) : DocumentTextElement

    /**
     * 表示されないインライン区間（ルビ注記・無効なインラインなど）。
     * [selector] は直前コンテキストの末尾値（未定なら null）、[rawBytes] は生バイト列。
     */
    data class SkippedInlineText(
        val selector: Int?,
        val text: String,
        val rawBytes: ByteArray,
    ) : DocumentTextElement {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SkippedInlineText) return false
            return selector == other.selector &&
                text == other.text &&
                rawBytes.contentEquals(other.rawBytes)
        }

        override fun hashCode(): Int {
            var result = selector ?: 0
            result = 31 * result + text.hashCode()
            result = 31 * result + rawBytes.contentHashCode()
            return result
        }
    }

    /**
     * 制御境界（制御コード/無効スカラー）。plain text では出力されないが、
     * マーカー走査の構造検証・ページ切れ等の区切りとして保持される。
     */
    data class ControlBoundary(val code: Int) : DocumentTextElement
}
