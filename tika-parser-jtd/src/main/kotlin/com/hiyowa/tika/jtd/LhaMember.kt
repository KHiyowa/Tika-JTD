package com.hiyowa.tika.jtd

/**
 * 1 個の LHA メンバの展開結果。OpenJTD rjtd-core/src/lha.rs の移植。
 *
 * Rust 側は `LhaMember { filename, packed_size, original_size, bytes }`（lha.rs 11-17行）で
 * 各 getter と [LhaMember.into_bytes] を持つが、Kotlin ではプロパティを public にする。
 *
 * @property filename ヘッダ中のメンバ名（lha.rs:13）。
 * @property packedSize 圧縮データ領域のバイト数（lha.rs:14）。
 * @property originalSize 展開後の（宣言された）バイト数（lha.rs:15）。
 * @property bytes 展開済みバイト列（lha.rs:16）。
 */
class LhaMember(
    val filename: String,
    val packedSize: Long,
    val originalSize: Long,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LhaMember) return false
        return filename == other.filename &&
            packedSize == other.packedSize &&
            originalSize == other.originalSize &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = filename.hashCode()
        result = 31 * result + packedSize.hashCode()
        result = 31 * result + originalSize.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "LhaMember(filename='$filename', packedSize=$packedSize, originalSize=$originalSize)"
}
