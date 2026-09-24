package com.hiyowa.tika.jtd

/**
 * JustCompressedDocument (JTD の圧縮ドキュメント全体包装) の展開。
 * OpenJTD rjtd-core/src/compressed_document.rs の移植（全83行）。
 *
 * 構造:
 * - 先頭に [JUST_COMPRESSED_DOCUMENT_MAGIC]（`0x26 0x00` + "JustCompressedDocument"、compressed_document.rs:4）
 * - 直後に 1 個の LHA メンバ。`-lh5-` マーカ (compressed_document.rs:5) の 2 バイト前
 *   （header_size バイトと 0 バイト）から始まる（compressed_document.rs:42-44）。
 */
object JustCompressedDocument {

    /**
     * compressed_document.rs:4 の JUST_COMPRESSED_DOCUMENT_MAGIC。
     * `0x26 0x00` + "JustCompressedDocument"（ASCII 22 バイト）。
     */
    val JUST_COMPRESSED_DOCUMENT_MAGIC: ByteArray =
        byteArrayOf(0x26, 0x00) + "JustCompressedDocument".toByteArray(Charsets.ISO_8859_1)

    /** compressed_document.rs:5 の LH5_METHOD（"-lh5-" 相当）。 */
    private val LH5_METHOD: ByteArray = "-lh5-".toByteArray(Charsets.ISO_8859_1)

    /**
     * JustCompressedDocument ペイロードか判定する。
     * compressed_document.rs:7-9 の is_just_compressed_document。
     */
    fun isJustCompressedDocument(data: ByteArray): Boolean {
        // compressed_document.rs:8: data.starts_with(JUST_COMPRESSED_DOCUMENT_MAGIC)
        return data.startsWith(JUST_COMPRESSED_DOCUMENT_MAGIC)
    }

    /**
     * デフォルト上限で展開する。
     * compressed_document.rs:11-13 の decompress_just_compressed_document。
     */
    fun decompressJustCompressedDocument(data: ByteArray): ByteArray =
        decompressJustCompressedDocumentWithLimits(data, ParseLimits.DEFAULT)

    /**
     * 明示的な [ParseLimits] で展開する。
     *
     * compressed_document.rs:15-25 の decompress_just_compressed_document_with_limits。
     * per-member LH5 出力・この呼び出しの total budget に与えられる全メンバが制限される。
     * 入力チェックは data が確保された後に行われる（compressed_document.rs:18）。
     */
    fun decompressJustCompressedDocumentWithLimits(data: ByteArray, limits: ParseLimits): ByteArray {
        val budget = limits.decompressionBudget() // compressed_document.rs:23
        return decompressWithBudget(data, budget) // compressed_document.rs:24
    }

    /**
     * 共有 [DecompressionBudget] で展開する。
     * compressed_document.rs:27-46 の decompress_just_compressed_document_with_budget の移植。
     *
     * 順序は Rust と同一（compressed_document.rs:31-45）:
     * 1. 入力長チェック（compressed_document.rs:31。探索「前」に行う）
     * 2. マジック検証（compressed_document.rs:32-36）
     * 3. "-lh5-" の探索（compressed_document.rs:38-41）
     * 4. member_start = method_offset - 2（compressed_document.rs:42-44）
     * 5. [Lh5Decoder.decompressLh5MemberWithBudget] へ委譲（compressed_document.rs:45）
     */
    internal fun decompressWithBudget(data: ByteArray, budget: DecompressionBudget): ByteArray {
        // compressed_document.rs:31
        budget.checkInputSize(data.size.toLong())
        if (!isJustCompressedDocument(data)) { // compressed_document.rs:32-36
            throw InvalidDataException("missing JustCompressedDocument marker")
        }

        // compressed_document.rs:38-41: data.windows(5).position(...)
        val methodOffset = data.indexOfWindow(LH5_METHOD)
            ?: throw InvalidDataException("missing -lh5- member marker")
        // compressed_document.rs:42-44: method_offset.checked_sub(2)
        if (methodOffset < 2) {
            throw InvalidDataException("invalid -lh5- member marker offset")
        }
        val memberStart = methodOffset - 2
        // compressed_document.rs:45: decompress_lh5_member_with_budget(&data[member_start..], budget)
        return Lh5Decoder.decompressLh5MemberWithBudget(data.copyOfRange(memberStart, data.size), budget)
            .bytes
    }

    /**
     * [haystack] 中の [needle] の最初の出現位置（Rust の windows(n).position 相当）。
     * なければ null。
     */
    private fun ByteArray.indexOfWindow(needle: ByteArray): Int? {
        if (needle.isEmpty()) return 0
        if (size < needle.size) return null
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) {
                if (this[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }

    /** [prefix] 始まりか（JtdFormatDetector.kt:64-66 の同型ヘルパと同義）。 */
    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
