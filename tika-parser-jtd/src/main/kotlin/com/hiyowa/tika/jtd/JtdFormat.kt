package com.hiyowa.tika.jtd

/**
 * JTD フォーマット検出の 5 段階判定。
 *
 * OpenJTD (rjtd-core/src/format.rs) の移植。
 * 判定順序:
 * 1. CFB マジックなし -> [UNKNOWN]
 * 2. CFB で `/DocumentText` が読める -> [COMPOUND_DOCUMENT_TEXT]
 * 3. `/JSCompDocument` が JustCompressedDocument マジック -> [COMPOUND_JUST_COMPRESSED_DOCUMENT]
 * 4. 任意ストリームに `SsmgV.01` を内蔵 -> [COMPOUND_EMBEDDED_DOCUMENT_TEXT]
 * 5. いずれにも該当しない CFB -> [COMPOUND_UNKNOWN]
 */
enum class JtdFormat(val asString: String) {
    COMPOUND_DOCUMENT_TEXT("cfb-document-text"),
    COMPOUND_EMBEDDED_DOCUMENT_TEXT("cfb-embedded-document-text"),
    COMPOUND_JUST_COMPRESSED_DOCUMENT("cfb-just-compressed-document"),
    COMPOUND_UNKNOWN("cfb-unknown"),
    UNKNOWN("unknown"),
}
