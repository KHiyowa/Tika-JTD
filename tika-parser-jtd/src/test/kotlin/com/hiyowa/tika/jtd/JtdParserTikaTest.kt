package com.hiyowa.tika.jtd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tika 統合ポイント（JtdParser）のテスト。
 *
 * README の約束「JAR を追加するだけで .jtd が透過的に認識される」を
 * AutoDetectParser 経由で検証する（移行レポート 第 6 節の契約準拠）。
 * ※ Tika 4 では parse() のストリーム型が TikaInputStream になったことに注意。
 */
class JtdParserTikaTest {

    private fun markerDocumentText(): ByteArray {
        // SsmgV.01 + 0x001f マーカー + 「銀河鉄道\n」の本文ストリーム
        val bytes = mutableListOf<Byte>()
        bytes.addAll("SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        bytes.add(0x00)
        bytes.add(0x1f)
        bytes.addAll("銀河鉄道\n".toByteArray(Charsets.UTF_16BE).toList())
        return bytes.toByteArray()
    }

    private fun cfbWithStream(path: String, payload: ByteArray): ByteArray {
        POIFSFileSystem().use { fs ->
            val parts = path.trimStart('/').split('/')
            var dir: DirectoryEntry = fs.root
            for (i in 0 until parts.size - 1) {
                dir = dir.createDirectory(parts[i])
            }
            dir.createDocument(parts.last(), ByteArrayInputStream(payload))
            val out = ByteArrayOutputStream()
            fs.writeFilesystem(out)
            return out.toByteArray()
        }
    }

    @Test
    fun autoDetectParsesJtdByResourceName() {
        val data = cfbWithStream("/DocumentText", markerDocumentText())

        val parser = AutoDetectParser()
        val handler = BodyContentHandler(-1)
        val metadata = Metadata()
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "sample.jtd")

        TikaInputStream.get(ByteArrayInputStream(data)).use { stream ->
            parser.parse(stream, handler, metadata, ParseContext())
        }

        assertContains(handler.toString(), "銀河鉄道")
        assertEquals("application/vnd.justsystems.ichitaro", metadata.get("Content-Type"))
    }

    @Test
    fun parserClaimsOleStorageWithoutFileName() {
        val data = cfbWithStream("/DocumentText", markerDocumentText())

        val parser = AutoDetectParser()
        val handler = BodyContentHandler(-1)
        val metadata = Metadata()

        TikaInputStream.get(ByteArrayInputStream(data)).use { stream ->
            parser.parse(stream, handler, metadata, ParseContext())
        }

        assertContains(handler.toString(), "銀河鉄道")
        assertEquals("application/vnd.justsystems.ichitaro", metadata.get("Content-Type"))
    }

    @Test
    fun jtdParserReportsFormatAsCustomMetadata() {
        val data = cfbWithStream("/DocumentText", markerDocumentText())

        val parser = JtdParser()
        val handler = BodyContentHandler(-1)
        val metadata = Metadata()

        TikaInputStream.get(ByteArrayInputStream(data)).use { stream ->
            parser.parse(stream, handler, metadata, ParseContext())
        }

        assertContains(handler.toString(), "銀河鉄道")
        assertEquals("cfb-document-text", metadata.get("X-JTD-Format"))
    }

    @Test
    fun unsupportedDataThrowsUnsupportedFormat() {
        val parser = JtdParser()
        val handler = BodyContentHandler(-1)
        val metadata = Metadata()

        assertFailsWith<UnsupportedFormatException> {
            TikaInputStream.get(ByteArrayInputStream("not a jtd".toByteArray(Charsets.ISO_8859_1))).use { stream ->
                parser.parse(stream, handler, metadata, ParseContext())
            }
        }
    }
}
