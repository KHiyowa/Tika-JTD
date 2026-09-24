package com.hiyowa.tika.jtd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * OpenJTD rjtd-core/src/format.rs の tests を移植したもの。
 *
 * CFB フィクスチャの合成は cfb::CompoundFile::create → POI POIFSFileSystem に置き換えている
 * （移行レポート 3 節「テストの哲学は同一、合成ヘルパのみ差し替え」）。
 *
 * 注: POI 5.5.1 には DirectoryEntry#getOrCreateDirectory と POIFSFileSystem#writeOver が存在しないため、
 * 単一ストリーム合成では等価な createDirectory / writeFilesystem を使用する。
 */
class JtdFormatDetectorTest {

    @Test
    fun detectsUnknownNonCfbData() {
        assertEquals(JtdFormat.UNKNOWN, JtdFormatDetector.detect("not cfb".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun detectsDocumentTextCfb() {
        val format = JtdFormatDetector.detect(cfbWithStream("/DocumentText", "SsmgV.01".toByteArray(Charsets.ISO_8859_1)))
        assertEquals(JtdFormat.COMPOUND_DOCUMENT_TEXT, format)
    }

    @Test
    fun detectsJustCompressedDocumentCfb() {
        val payload = byteArrayOf(0x26, 0x00) + "JustCompressedDocument".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0x00) + "-lh5-".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x00) + "payload".toByteArray(Charsets.ISO_8859_1)
        val format = JtdFormatDetector.detect(cfbWithStream("/JSCompDocument", payload))
        assertEquals(JtdFormat.COMPOUND_JUST_COMPRESSED_DOCUMENT, format)
    }

    @Test
    fun detectsEmbeddedDocumentTextCfb() {
        val embedded = "SsmgV.01".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0x00, 0x1f) +
            "Note".toByteArray(Charsets.UTF_16BE)
        val format = JtdFormatDetector.detect(cfbWithStream("/JSSlipObject1", embedded))
        assertEquals(JtdFormat.COMPOUND_EMBEDDED_DOCUMENT_TEXT, format)
    }

    /** cfb::CompoundFile::create と同じく、単一ストリームを含む CFB をメモリ合成する。 */
    private fun cfbWithStream(path: String, payload: ByteArray): ByteArray {
        POIFSFileSystem().use { fs ->
            val parts = path.trimStart('/').split('/')
            var parent: DirectoryEntry = fs.root
            for (part in parts.dropLast(1)) {
                parent = parent.createDirectory(part)
            }
            parent.createDocument(parts.last(), ByteArrayInputStream(payload))
            val out = ByteArrayOutputStream()
            fs.writeFilesystem(out)
            return out.toByteArray()
        }
    }
}
