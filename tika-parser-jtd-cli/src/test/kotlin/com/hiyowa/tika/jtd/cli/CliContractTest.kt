package com.hiyowa.tika.jtd.cli

import java.io.ByteArrayOutputStream
import java.io.File
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tika CLI (`tika-app` 互換) 契約テスト。
 *
 * 標準オプション（--text, --metadata, --jsonRecursive, --xml, --help 等）の契約受入。
 */
class CliContractTest {

    private fun markerDocumentText(text: String): ByteArray {
        val bytes = mutableListOf<Byte>()
        bytes.addAll("SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        bytes.add(0x00)
        bytes.add(0x1f)
        bytes.addAll(text.toByteArray(Charsets.UTF_16BE).toList())
        return bytes.toByteArray()
    }

    private fun jtdFile(name: String, entries: Map<String, ByteArray>): File {
        val file = File.createTempFile(name, ".jtd")
        file.deleteOnExit()
        POIFSFileSystem().use { fs ->
            for ((path, payload) in entries) {
                val parts = path.trimStart('/').split('/')
                var dir: org.apache.poi.poifs.filesystem.DirectoryEntry = fs.root
                for (i in 0 until parts.size - 1) {
                    dir = try {
                        dir.getEntry(parts[i]) as org.apache.poi.poifs.filesystem.DirectoryEntry
                    } catch (e: java.io.FileNotFoundException) {
                        dir.createDirectory(parts[i])
                    } catch (e: IllegalArgumentException) {
                        dir.createDirectory(parts[i])
                    }
                }
                dir.createDocument(parts.last(), java.io.ByteArrayInputStream(payload))
            }
            file.outputStream().use { fs.writeFilesystem(it) }
        }
        return file
    }

    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = Cli.run(args.toList(), out, err)
        return Triple(code, out.toByteArray().toString(Charsets.UTF_8), err.toByteArray().toString(Charsets.UTF_8))
    }

    @Test
    fun textOptionPrintsBodyTextToStdout() {
        val file = jtdFile("text", mapOf("/DocumentText" to markerDocumentText("銀河鉄道の夜\n宮沢賢治")))
        val (codeLong, outLong, _) = run("--text", file.absolutePath)
        val (codeShort, outShort, _) = run("-t", file.absolutePath)
        assertEquals(0, codeLong)
        assertEquals(0, codeShort)
        assertEquals(outLong, outShort)
        assertEquals("銀河鉄道の夜\n宮沢賢治", outLong.trim())
    }

    @Test
    fun missingFileFails() {
        val (code, _, err) = run("--text", "/nonexistent/path/document.jtd")
        assertNotEquals(0, code)
        assertTrue(err.isNotEmpty(), "エラーは stderr へ")
    }

    @Test
    fun metadataOptionEmitsMetadata() {
        val file = jtdFile("meta", mapOf("/DocumentText" to markerDocumentText("ジョバンニ")))
        val (code, out, _) = run("--metadata", file.absolutePath)
        assertEquals(0, code)
        assertTrue(out.contains("Content-Type: application/vnd.justsystems.ichitaro") || out.contains("application/vnd.justsystems.ichitaro"), "actual: $out")
    }

    @Test
    fun jsonRecursiveOptionEmitsJson() {
        val file = jtdFile("json", mapOf("/DocumentText" to markerDocumentText("カムパネルラ")))
        val (code, out, _) = run("--jsonRecursive", file.absolutePath)
        assertEquals(0, code)
        assertTrue(out.trimStart().startsWith("[") || out.trimStart().startsWith("{"), "JSON 出力であること: $out")
        assertTrue(out.contains("カムパネルラ"), "抽出テキストが含まれること: $out")
    }

    @Test
    fun xmlOptionEmitsXhtml() {
        val file = jtdFile("xml", mapOf("/DocumentText" to markerDocumentText("白鳥の停車場")))
        val (code, out, _) = run("--xml", file.absolutePath)
        assertEquals(0, code)
        assertTrue(out.contains("<html") && out.contains("白鳥の停車場"), "XHTML 出力であること: $out")
    }

    @Test
    fun helpExitsZero() {
        for (arg in listOf("help", "-h", "--help", "-?")) {
            val (code, out, _) = run(arg)
            assertEquals(0, code, "$arg は exit 0")
            assertTrue(out.contains("usage: java -jar tika-app.jar") || out.contains("--text") || out.contains("-t"), "actual: $out")
        }
    }

    @Test
    fun noArgsExitsNonZero() {
        val (codeNo, _, err) = run()
        assertNotEquals(0, codeNo)
        assertTrue(err.isNotEmpty())
    }
}
