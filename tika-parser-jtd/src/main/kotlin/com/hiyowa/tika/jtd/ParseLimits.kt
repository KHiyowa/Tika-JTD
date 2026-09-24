package com.hiyowa.tika.jtd

/**
 * 不変なリソース上限セット。OpenJTD rjtd-core/src/limits.rs の移植（必要な部分のみ）。
 *
 * Rust 側は `Copy` 値セマンティクスの builder だが、Kotlin では immutable クラスとし、
 * `withXxx` が毎回新しいインスタンスを返す形で再現する（limits.rs 115-151）。
 *
 * 入力チェックは呼び出し側が `[u8]` を確保した後のもの（limits.rs 35-40 の doc 同一）。
 */
class ParseLimits(
    /** limits.rs:43（DEFAULT 値: 93 行目） */
    val maxInputBytes: Long,
    /** 1 つの LH5 メンバあたりの展開出力上限（limits.rs:44、DEFAULT 94 行目） */
    val maxDecompressedBytes: Long,
    /** 共有予算内の累計展開出力上限（limits.rs:45、DEFAULT 95 行目） */
    val maxTotalDecompressedBytes: Long,
    /** 展開比上限 original <= packed * ratio（limits.rs:46、DEFAULT 96 行目） */
    val maxDecompressionRatio: Long,
    /** 展開比判定の下限フォロア。ratio_limit = max(packed * ratio, floor)（limits.rs:47、DEFAULT 97 行目） */
    val decompressionRatioFloorBytes: Long,
) {
    companion object {
        /** limits.rs:3 の MEBIBYTE 相当。 */
        internal const val MEBIBYTE: Long = 1024L * 1024L

        /**
         * limits.rs:92-109 の DEFAULT。
         *
         * TODO(Step-limits): 残り 10 種のリソース上限（limits.rs:48-58 の max_streams /
         * max_stream_bytes / max_records / max_record_bytes / max_images / max_image_bytes /
         * max_image_width / max_image_height / max_image_pixels / max_pages / max_page_lines）は
         * 後続の Step で移植する。
         */
        val DEFAULT: ParseLimits = ParseLimits(
            maxInputBytes = 64 * MEBIBYTE,
            maxDecompressedBytes = 256 * MEBIBYTE,
            maxTotalDecompressedBytes = 256 * MEBIBYTE,
            maxDecompressionRatio = 256,
            decompressionRatioFloorBytes = MEBIBYTE,
        )
    }

    /** limits.rs:115-118 の with_max_input_bytes。新しいインスタンスを返す。 */
    fun withMaxInputBytes(maxInputBytes: Long): ParseLimits =
        ParseLimits(maxInputBytes, maxDecompressedBytes, maxTotalDecompressedBytes, maxDecompressionRatio, decompressionRatioFloorBytes)

    /** limits.rs:121-124 の with_max_decompressed_bytes。 */
    fun withMaxDecompressedBytes(maxDecompressedBytes: Long): ParseLimits =
        ParseLimits(maxInputBytes, maxDecompressedBytes, maxTotalDecompressedBytes, maxDecompressionRatio, decompressionRatioFloorBytes)

    /** limits.rs:132-138 の with_max_total_decompressed_bytes。 */
    fun withMaxTotalDecompressedBytes(maxTotalDecompressedBytes: Long): ParseLimits =
        ParseLimits(maxInputBytes, maxDecompressedBytes, maxTotalDecompressedBytes, maxDecompressionRatio, decompressionRatioFloorBytes)

    /** limits.rs:140-143 の with_max_decompression_ratio。 */
    fun withMaxDecompressionRatio(maxDecompressionRatio: Long): ParseLimits =
        ParseLimits(maxInputBytes, maxDecompressedBytes, maxTotalDecompressedBytes, maxDecompressionRatio, decompressionRatioFloorBytes)

    /** limits.rs:145-151 の with_decompression_ratio_floor_bytes。 */
    fun withDecompressionRatioFloorBytes(decompressionRatioFloorBytes: Long): ParseLimits =
        ParseLimits(maxInputBytes, maxDecompressedBytes, maxTotalDecompressedBytes, maxDecompressionRatio, decompressionRatioFloorBytes)

    /**
     * 入力サイズの上限チェック。limits.rs:218-220 の check_input_size。
     */
    fun checkInputSize(actual: Long) {
        checkResource("input bytes", maxInputBytes, actual)
    }

    /**
     * LH5 展開出力の事前チェック。limits.rs:222-232 の check_lh5_output_size。
     *
     * 1. メンバ単位の展開出力上限（"LH5 decompressed bytes"）
     * 2. 展開比上限 ratio_limit = max(packed_size.saturating_mul(ratio), floor)（"LH5 expansion bytes"）
     */
    internal fun checkLh5OutputSize(packedSize: Long, originalSize: Long) {
        checkResource("LH5 decompressed bytes", maxDecompressedBytes, originalSize) // limits.rs:223-227
        // limits.rs:228-230: packed_size.saturating_mul(ratio).max(floor) を checked 算術で再現
        val ratio = maxDecompressionRatio
        val ratioLimit = (
            if (ratio != 0L && packedSize > Long.MAX_VALUE / ratio) {
                Long.MAX_VALUE // saturating_mul の飽和（実際には無制限扱い）
            } else {
                packedSize * ratio
            }
            ).coerceAtLeast(decompressionRatioFloorBytes)
        checkResource("LH5 expansion bytes", ratioLimit, originalSize) // limits.rs:231-232
    }

    /**
     * この上限セットの総量 LH5 出力を強制するための共有予算を生成する。
     * limits.rs:237-242 の decompression_budget（累計は 0 で初期化）。
     */
    fun decompressionBudget(): DecompressionBudget =
        DecompressionBudget(this, 0L)
}

/**
 * limits.rs:444-454 の check_resource 相当。
 *
 * actual > limit のみで [ResourceLimitException] を投げる（limit 等しいは可）。
 */
internal fun checkResource(resource: String, limit: Long, actual: Long) {
    if (actual > limit) {
        throw ResourceLimitException(resource, limit, actual)
    }
}
