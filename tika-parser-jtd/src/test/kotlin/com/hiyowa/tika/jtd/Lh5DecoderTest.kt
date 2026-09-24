package com.hiyowa.tika.jtd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * OpenJTD rjtd-core/src/lha.rs の inline テスト移植（Step 3）。
 *
 * 手工合成ビット列（bits / lh5_member ヘルパ）を Rust からそのまま再現し、
 * -lh5- 単一リテラル展開と予算超過拒否を検証する。
 */
class Lh5DecoderTest {

    @Test
    fun decompressesSingleLiteralLh5Member() {
        val compressed = bits(
            listOf(
                BitValue(1, 16),
                BitValue(0, 5),
                BitValue(0, 5),
                BitValue(0, 9),
                BitValue('A'.code.toLong(), 9),
                BitValue(0, 4),
                BitValue(0, 4),
            ),
        )
        val member = lh5Member(compressed, 1)

        val decoded = Lh5Decoder.decompressLh5Member(member)

        assertEquals("", decoded.filename)
        assertEquals(compressed.size.toLong(), decoded.packedSize)
        assertEquals(1L, decoded.originalSize)
        assertEquals("A", decoded.bytes.toString(Charsets.ISO_8859_1))
    }

    @Test
    fun rejectsLh5MemberBeforeAllocatingOutputOverLimit() {
        // Given
        val compressed = bits(
            listOf(
                BitValue(1, 16),
                BitValue(0, 5),
                BitValue(0, 5),
                BitValue(0, 9),
                BitValue('A'.code.toLong(), 9),
                BitValue(0, 4),
                BitValue(0, 4),
            ),
        )
        val member = lh5Member(compressed, 2)
        val limits = ParseLimits.DEFAULT.withMaxDecompressedBytes(1)

        // When
        val ex = assertFailsWith<ResourceLimitException> {
            Lh5Decoder.decompressLh5MemberWithLimits(member, limits)
        }

        // Then
        assertEquals("LH5 decompressed bytes", ex.resource)
        assertEquals(1L, ex.limit)
        assertEquals(2L, ex.actual)
    }

    @Test
    fun rejectsSecondLh5MemberWhenCumulativeBudgetIsExhausted() {
        // Given
        val compressed = bits(
            listOf(
                BitValue(1, 16),
                BitValue(0, 5),
                BitValue(0, 5),
                BitValue(0, 9),
                BitValue('A'.code.toLong(), 9),
                BitValue(0, 4),
                BitValue(0, 4),
            ),
        )
        val member = lh5Member(compressed, 1)
        val limits = ParseLimits.DEFAULT
            .withMaxDecompressedBytes(1)
            .withMaxTotalDecompressedBytes(1)
        val budget = limits.decompressionBudget()

        // When
        val first = Lh5Decoder.decompressLh5MemberWithBudget(member, budget)
        val second = runCatching { Lh5Decoder.decompressLh5MemberWithBudget(member, budget) }

        // Then
        assertEquals("A", first.bytes.toString(Charsets.ISO_8859_1))
        val ex = assertFailsWith<ResourceLimitException> { second.getOrThrow() }
        assertEquals("total LH5 decompressed bytes", ex.resource)
        assertEquals(1L, ex.limit)
        assertEquals(2L, ex.actual)
    }

    // ---- テストヘルパ（Rust tests の lh5_member / bits を忠実に移植）----

    private data class BitValue(val value: Long, val count: Int)

    /** LHA 通常ヘッダ（ヘッダ長 22 minimal variant）+ 圧縮データを連結したメンバを作る。 */
    private fun lh5Member(compressed: ByteArray, originalSize: Long): ByteArray {
        val headerSize = 22
        val bytes = mutableListOf<Byte>()
        bytes.add(headerSize.toByte())
        bytes.add(0)
        bytes.addAll("-lh5-".toByteArray(Charsets.ISO_8859_1).toList())
        bytes.addAll(intLe(compressed.size.toLong()))
        bytes.addAll(intLe(originalSize))
        bytes.addAll(intLe(0))
        bytes.add(0x20)
        bytes.add(0)
        bytes.add(0)
        bytes.add(0) // u16 LE 0
        bytes.add(0)
        bytes.addAll(compressed.toList())
        return bytes.toByteArray()
    }

    private fun intLe(value: Long): List<Byte> =
        listOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )

    /** MSB-first でビット列をバイト列に詰め込む（Rust bits() と同一）。 */
    private fun bits(values: List<BitValue>): ByteArray {
        val out = mutableListOf<Byte>()
        var current = 0
        var used = 0
        for ((value, count) in values) {
            for (shift in count - 1 downTo 0) {
                current = (current shl 1) or (((value shr shift) and 1).toInt())
                used += 1
                if (used == 8) {
                    out.add((current and 0xFF).toByte())
                    current = 0
                    used = 0
                }
            }
        }
        if (used > 0) {
            out.add(((current shl (8 - used)) and 0xFF).toByte())
        }
        assertTrue(out.isNotEmpty())
        return out.toByteArray()
    }
}
