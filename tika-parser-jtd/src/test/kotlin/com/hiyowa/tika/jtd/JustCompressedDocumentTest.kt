package com.hiyowa.tika.jtd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OpenJTD rjtd-core/src/compressed_document.rs の inline テスト移植（Step 3）。
 */
class JustCompressedDocumentTest {

    @Test
    fun detectsJustCompressedDocumentPayload() {
        val payload = byteArrayOf(0x26, 0x00) + "JustCompressedDocument".toByteArray(Charsets.ISO_8859_1) +
            "payload".toByteArray(Charsets.ISO_8859_1)
        assertTrue(JustCompressedDocument.isJustCompressedDocument(payload))
        assertFalse(JustCompressedDocument.isJustCompressedDocument("DocumentText".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun rejectsOversizedWrapperBeforeScanningForLh5Member() {
        val magic = byteArrayOf(0x26, 0x00) + "JustCompressedDocument".toByteArray(Charsets.ISO_8859_1)
        val data = magic + "tail".toByteArray(Charsets.ISO_8859_1)
        val limit = data.size.toLong() - 1

        val ex = assertFailsWith<ResourceLimitException> {
            JustCompressedDocument.decompressJustCompressedDocumentWithLimits(
                data,
                ParseLimits.DEFAULT.withMaxInputBytes(limit),
            )
        }

        assertEquals("input bytes", ex.resource)
        assertEquals(limit, ex.limit)
        assertEquals(data.size.toLong(), ex.actual)
    }
}
