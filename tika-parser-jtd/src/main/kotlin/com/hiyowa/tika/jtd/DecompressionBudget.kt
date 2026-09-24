package com.hiyowa.tika.jtd

/**
 * 1 ドキュメント走査で累計 LH5 出力を管理する共有予算。
 * OpenJTD rjtd-core/src/limits.rs の移植（61-90 行の型定義 + 262-290 行の impl）。
 *
 * [ParseLimits.decompressionBudget] から生成する。ドキュメント上の LHA メンバすべてが
 * 同一予算を共有して累計上限（"total LH5 decompressed bytes"）に抵触する。
 */
class DecompressionBudget internal constructor(
    private val limits: ParseLimits,
    /** ここまでの LH5 累計展開出力バイト数（limits.rs:69 の decompressed_bytes）。 */
    private var decompressedBytes: Long,
) {
    /**
     * 入力サイズの上限チェック。limits.rs:263-265（ParseLimits へ委譲）。
     */
    fun checkInputSize(size: Long) {
        limits.checkInputSize(size)
    }

    /**
     * 1 LH5 メンバの展開出力を予約する。limits.rs:267-289 の reserve_lh5_output を忠実に移植。
     *
     * 3 条件（メンバ単位の per-member 上限、展開比、累計上限）を checked 算術で逐次判定し、
     * すべて通過して初めて [decompressedBytes] をコミットする。
     * いずれか 1 つでも失敗したら何のコミットもせず（ResourceBudget::reserve_pair と同じ
     * 「片方失敗なら両方コミットしない」の原子性、limits.rs:363-375 の精神）。
     */
    fun reserveLh5Output(packedSize: Long, originalSize: Long) {
        // limits.rs:272-273: per-member 上限 + 展開比チェック
        limits.checkLh5OutputSize(packedSize, originalSize)

        // limits.rs:274-281: checked_add。オーバーフロー時は actual = usize::MAX を報告する
        val actual = if (decompressedBytes > Long.MAX_VALUE - originalSize) {
            throw ResourceLimitException(
                resource = "total LH5 decompressed bytes",
                limit = limits.maxTotalDecompressedBytes,
                actual = Long.MAX_VALUE,
            )
        } else {
            decompressedBytes + originalSize
        }

        // limits.rs:282-286: 累計上限チェック
        checkResource("total LH5 decompressed bytes", limits.maxTotalDecompressedBytes, actual)

        // limits.rs:287: 全チェック通過後にのみコミット
        decompressedBytes = actual
    }

    /**
     * TODO(Step-limits): limits.rs 292-436 の ResourceBudget（streams / records / images /
     * pages などの汎用会計）は本 Step では移植していない。
     */
}
