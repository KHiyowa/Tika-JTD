package com.hiyowa.tika.jtd.cli

import java.io.ByteArrayOutputStream
import java.io.File
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * CLI 契約テスト（移行レポート 2.5 / 第 6 節⑤⑥）。
 *
 * OpenJTD rjtd-cli の slim 契約（cat / export txt|text / sheets / help exit 0）の移植と、
 * 新 README が約束する export --format json の受け入れ。
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
    fun catPrintsBodyTextToStdout() {
        val file = jtdFile("cat", mapOf("/DocumentText" to markerDocumentText("銀河鉄道の夜\n宮沢賢治")))
        val (code, out, _) = run("cat", file.absolutePath)
        assertEquals(0, code)
        assertEquals("銀河鉄道の夜\n宮沢賢治", out.trim())
        assertTrue(out.startsWith("銀河鉄道の夜"))
    }

    @Test
    fun catMissingFileFails() {
        val (code, _, err) = run("cat", "/nonexistent/path/document.jtd")
        assertNotEquals(0, code)
        assertTrue(err.isNotEmpty(), "エラーは stderr へ")
    }

    @Test
    fun exportTxtAndTextAliasesMatchCatByteForByte() {
        val file = jtdFile("export", mapOf("/DocumentText" to markerDocumentText("カムパネルラが手をあげました。\n")))
        val (codeCat, outCat, _) = run("cat", file.absolutePath)
        val (codeTxt, outTxt, _) = run("export", file.absolutePath, "--format", "txt")
        val (codeText, outText, _) = run("export", file.absolutePath, "--format", "text")
        assertEquals(0, codeCat)
        assertEquals(0, codeTxt)
        assertEquals(0, codeText)
        assertEquals(outCat, outTxt)
        assertEquals(outTxt, outText)
    }

    @Test
    fun exportRejectsNonTextFormats() {
        val file = jtdFile("reject", mapOf("/DocumentText" to markerDocumentText("ジョバンニ\n")))
        for (fmt in listOf("pdf", "html", "md", "docx")) {
            val (code, _, err) = run("export", file.absolutePath, "--format", fmt)
            assertNotEquals(0, code, "--format $fmt は拒否すべき")
            assertTrue(err.contains("unsupported"), "actual: $err")
        }
    }

    @Test
    fun exportJsonEmitsMetadataAndText() {
        val file = jtdFile("json", mapOf("/DocumentText" to markerDocumentText("銀河鉄道の夜\n宮沢賢治")))
        val (code, out, _) = run("export", file.absolutePath, "--format", "json")
        assertEquals(0, code)
        // Jackson 等に依存しない最小 JSON: text と metadata を含む
        assertTrue(out.trimStart().startsWith("{") && out.trimEnd().endsWith("}"), "actual: $out")
        assertTrue(out.contains("\"text\""), "actual: $out")
        assertTrue(out.contains("銀河鉄道の夜"), "actual: $out")
        assertTrue(out.contains("application/vnd.justsystem.ichitaro"), "actual: $out")
    }

    @Test
    fun exportSheetSelectsByIndexAndName() {
        val docItemInfo = mutableListOf<Byte>()
        fun le32(v: Long) = listOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
        docItemInfo.addAll(le32(1))
        docItemInfo.addAll(le32(1))
        val name = "二、活版所".toByteArray(Charsets.UTF_16LE)
        docItemInfo.addAll(le32((name.size / 2).toLong()))
        docItemInfo.addAll(name.toList())
        docItemInfo.addAll(le32(7))
        docItemInfo.addAll(ByteArray(26).toList())

        val file = jtdFile(
            "sheets",
            mapOf(
                "/DocumentText" to markerDocumentText("銀河鉄道の夜\n宮沢賢治"),
                "/ObjectSheets/DocSheet/DocItemInfo" to docItemInfo.toByteArray(),
                "/ObjectSheets/DocSheet/DOCS_0007/DocumentText" to markerDocumentText("ジョバンニは窓をあけました。\n"),
            ),
        )

        val (codeIdx, outIdx, _) = run("export", file.absolutePath, "--format", "txt", "--sheet", "1")
        assertEquals(0, codeIdx)
        assertEquals("ジョバンニは窓をあけました。", outIdx.trim())

        val (codeName, outName, _) = run("export", file.absolutePath, "--format", "txt", "--sheet", "二、活版所")
        assertEquals(0, codeName)
        assertEquals(outIdx, outName)
    }

    @Test
    fun sheetsListsTsvRows() {
        val file = jtdFile(
            "sheetslist",
            mapOf(
                "/DocumentText" to markerDocumentText("銀河鉄道の夜\n"),
                "/ObjectSheets/DocSheet/DocItemInfo" to run {
                    val d = mutableListOf<Byte>()
                    fun le32(v: Long) = listOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
                    d.addAll(le32(1))
                    d.addAll(le32(1))
                    val name = "一、午後の授業".toByteArray(Charsets.UTF_16LE)
                    d.addAll(le32((name.size / 2).toLong()))
                    d.addAll(name.toList())
                    d.addAll(le32(0))
                    d.addAll(ByteArray(26).toList())
                    d.toByteArray()
                },
            ),
        )
        val (code, out, _) = run("sheets", file.absolutePath)
        assertEquals(0, code)
        // 末尾改行だけを除去する（trimEnd は末尾タブも削ってしまうため不可）
        val lines = out.removeSuffix("\n").split('\n')
        assertEquals(2, lines.size)
        assertEquals("sheet\t0\tタイトル\t\t", lines[0])
        assertEquals("sheet\t1\t一、午後の授業\t/ObjectSheets/DocSheet/DOCS_0000\t", lines[1])
    }

    @Test
    fun helpExitsZeroForTikaHealthCheck() {
        for (arg in listOf("help", "-h", "--help")) {
            val (code, out, _) = run(arg)
            assertEquals(0, code, "$arg は exit 0")
            assertTrue(out.contains("cat") && out.contains("export") && out.contains("sheets"))
        }
    }

    @Test
    fun unknownCommandOrNoArgsExitsNonZero() {
        val (codeNo, _, _) = run()
        assertNotEquals(0, codeNo)
        val (codeUnknown, _, err) = run("frobnicate", "x")
        assertNotEquals(0, codeUnknown)
        assertTrue(err.isNotEmpty())
    }

    @Test
    fun brokenJttcFailsGracefully() {
        // 不正 JTTC（JSCompDocument だが -lh5- ヘッダが壊れている）は nonzero で失敗
        val comp = byteArrayOf(0x26, 0x00) + "JustCompressedDocument".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0x00) + "-lh5-".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x00) + "broken".toByteArray(Charsets.ISO_8859_1)
        val file = jtdFile("broken", mapOf("/JSCompDocument" to comp))
        val (code, _, err) = run("cat", file.absolutePath)
        assertNotEquals(0, code)
        assertTrue(err.contains("invalid data"), "actual: $err")
    }
}
