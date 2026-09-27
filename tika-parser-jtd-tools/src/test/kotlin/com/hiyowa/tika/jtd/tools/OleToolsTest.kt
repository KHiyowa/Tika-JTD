package com.hiyowa.tika.jtd.tools

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * OLE2 補助ツール（list / dump / magic）の振る舞いテスト。
 * テスト用コンテナはすべて合成生成であり、実コーパスには依存しない。
 */
class OleToolsTest {

    /** マジック識別の仕様テスト。 */
    @Nested
    inner class Magic {

        @Test
        fun detectsUtf16MathVafAtZero() {
            val data = "MATH.VAF".toByteArray(StandardCharsets.UTF_16LE) + byteArrayOf(1, 2, 3)
            val d = StreamMagic.detect(data)
            assertEquals("MATH.VAF", d.label)
            assertEquals(0L, d.offset)
        }

        @Test
        fun detectsJsSnapshot() {
            val data = "JSSnapShot32".toByteArray(StandardCharsets.US_ASCII) + ByteArray(64)
            assertEquals("JSSnapShot32", StreamMagic.detect(data).label)
        }

        @Test
        fun detectsMetafileAndWmfCore() {
            val meta = "METAFILE".toByteArray(StandardCharsets.US_ASCII) + ByteArray(16)
            assertEquals("METAFILE", StreamMagic.detect(meta).label)

            val wmf = byteArrayOf(0x01, 0x00, 0x09, 0x00, 0x00, 0x03)
            assertEquals("WMF", StreamMagic.detect(wmf).label)
        }

        @Test
        fun detectsPngWithinHeaderWindowAndReportsOffset() {
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            val header = ByteArray(93)
            val data = header + png + ByteArray(32)
            val d = StreamMagic.detect(data)
            assertEquals("PNG", d.label)
            assertEquals(93L, d.offset)
        }

        @Test
        fun detectsEmfByRecordHeaderShape() {
            val emf = ByteArray(96)
            // EMR_HEADER: type=1, nBytes=96
            emf[0] = 0x01
            emf[4] = 96.toByte()
            assertEquals("EMF", StreamMagic.detect(emf).label)
        }

        @Test
        fun fallsBackToHexLabelForUnknown() {
            val data = byteArrayOf(0x00, 0x00, 0x01, 0x00, 0x02, 0x00, 0x03, 0x00)
            val d = StreamMagic.detect(data)
            assertTrue(d.label.startsWith("UNKNOWN"), "actual=${d.label}")
            assertEquals(0L, d.offset)
        }

        @Test
        fun handlesEmptyAndTinyInput() {
            assertEquals("EMPTY", StreamMagic.detect(ByteArray(0)).label)
            assertEquals("UNKNOWN", StreamMagic.detect(byteArrayOf(0x01)).label)
        }
    }

    /** 走査順・パス表記・件数集計の仕様テスト。 */
    @Nested
    inner class Scan {

        @TempDir
        lateinit var dir: Path

        @Test
        fun walksDeterministicallyDirsFirstThenDocsSortedByLowercase() {
            val input = dir.resolve("synthetic.xls.bin")
            buildContainer(input)

            val entries = OleScanner.scan(input)
            val paths = entries.map { "${it.kind} ${it.path}" }

            val expect = listOf(
                // ルート: ディレクトリ（名前昇順）を再帰 → 文書（昇順）
                "DIR /One",
                "DIR /One/Deep",
                "DOC /One/Deep/math",
                "DOC /One/\u0001CompObj",
                "DOC /One/\u0003Contents",
                "DIR /Two",
                "DOC /Two/ plain",
                "DOC /Two/plain",
                "DOC /RootDoc",
            )
            assertEquals(expect, paths)
        }

        @Test
        fun reportsChildCountAndSize() {
            val input = dir.resolve("synthetic.xls.bin")
            buildContainer(input)
            val byPath = OleScanner.scan(input).associateBy { it.path }
            // /One の子 = Deep(ディレクトリ) + \u0001CompObj + \u0003Contents
            assertEquals(3, byPath.getValue("/One").childCount)
            assertEquals(-1L, byPath.getValue("/One").size)
            assertEquals(5L, byPath.getValue("/RootDoc").size)
        }

        private fun buildContainer(target: Path) {
            val fs = POIFSFileSystem()
            fs.root.createDocument("RootDoc", ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5)))
            val one = fs.root.createDirectory("One")
            val deep = one.createDirectory("Deep")
            deep.createDocument(
                "math",
                ByteArrayInputStream("MATH.VAF".toByteArray(StandardCharsets.UTF_16LE) + ByteArray(8)),
            )
            one.createDocument("\u0001CompObj", ByteArrayInputStream("type-info".toByteArray()))
            one.createDocument("\u0003Contents", ByteArrayInputStream(pngAfterHeader(93)))
            val two = fs.root.createDirectory("Two")
            two.createDocument(" plain", ByteArrayInputStream(byteArrayOf(6, 7, 8)))
            two.createDocument("plain", ByteArrayInputStream(byteArrayOf(9)))
            Files.newOutputStream(target).use { fs.writeFilesystem(it) }
            fs.close()
        }
    }

    /** ダンプ（フラット展開・ファイル名サニタイズ・サイズ上限）の仕様テスト。 */
    @Nested
    inner class Dump {

        @TempDir
        lateinit var dir: Path

        @Test
        fun sanitizesControlCharsAndPreservesBytes() {
            val input = dir.resolve("synthetic.xls.bin")
            val outDir = dir.resolve("out")
            buildSimpleContainer(input)

            val result = OleDumper.dump(input, outDir)
            assertEquals(4, result.written.size)

            val names = result.written.map { it.fileName.toString() }
            // 制御文字は %XX エスケープ。スペースとスラッシュはアンダースコア
            assertTrue(names.any { it.contains("%01CompObj") }, "actual=$names")
            assertTrue(names.any { it.contains("%03Contents") }, "actual=$names")
            assertFalse(names.any { name -> name.toByteArray().any { b -> b.toInt() in 0x01..0x1f } })
            assertFalse(names.any { it.contains('/') })

            val mathFile = result.written.first { it.fileName.toString().endsWith("_math") }
            assertTrue(
                String(Files.readAllBytes(mathFile), StandardCharsets.UTF_16LE).startsWith("MATH.VAF"),
            )
        }

        @Test
        fun skipsStreamOverLimitWithoutWriting() {
            val input = dir.resolve("synthetic.xls.bin")
            val outDir = dir.resolve("out")
            buildSimpleContainer(input)

            // /One/\u0003Contents は 93+40 バイト → 上限 64 バイトでスキップ
            val result = OleDumper.dump(input, outDir, maxBytes = 64L)
            assertTrue(result.written.none { it.fileName.toString().contains("%03Contents") })
            assertTrue(result.skipped.any { it.first.endsWith("/\u0003Contents") })
        }

        private fun buildSimpleContainer(target: Path) {
            val fs = POIFSFileSystem()
            fs.root.createDocument("RootDoc", ByteArrayInputStream(byteArrayOf(1, 2, 3)))
            val one = fs.root.createDirectory("One")
            one.createDocument("\u0001CompObj", ByteArrayInputStream("type-info".toByteArray()))
            one.createDocument("\u0003Contents", ByteArrayInputStream(pngAfterHeader(93) + ByteArray(32)))
            val deep = one.createDirectory("Deep")
            deep.createDocument(
                "math",
                ByteArrayInputStream("MATH.VAF".toByteArray(StandardCharsets.UTF_16LE)),
            )
            Files.newOutputStream(target).use { fs.writeFilesystem(it) }
            fs.close()
        }
    }

    /** CLI エントリポイントの仕様テスト。 */
    @Nested
    inner class Cli {

        @TempDir
        lateinit var dir: Path

        @Test
        fun listPrintsTreeAndExitsZero() {
            val input = dir.resolve("synthetic.xls.bin")
            buildCliContainer(input)
            val (code, out, _) = capture(listOf("list", input.toString()))
            assertEquals(0, code)
            assertTrue(out.contains("DIR  /One"))
            assertTrue(out.contains("size=4"))
        }

        @Test
        fun magicNeverLeaksPayloadContent() {
            val input = dir.resolve("synthetic.xls.bin")
            // ヘッダに差し出し元らしき絶対パスを仕込む（PII 混入想定のガード）
            val withPath = "C:\\Users\\someone\\Desktop\\figure.png".toByteArray(StandardCharsets.US_ASCII)
            val padded = withPath + ByteArray(93 - withPath.size) + PNG_MAGIC + ByteArray(16)
            val fs = POIFSFileSystem()
            val one = fs.root.createDirectory("One")
            one.createDocument("\u0003Contents", ByteArrayInputStream(padded))
            Files.newOutputStream(input).use { fs.writeFilesystem(it) }
            fs.close()

            val (code, out, _) = capture(listOf("magic", input.toString()))
            assertEquals(0, code)
            assertTrue(out.contains("PNG"), "actual=$out")
            assertFalse(out.contains("Users"), "PII リーク: $out")
        }

        @Test
        fun dumpWritesFilesAndReportsThem() {
            val input = dir.resolve("synthetic.xls.bin")
            val outDir = dir.resolve("out")
            buildCliContainer(input)
            val (code, out, _) = capture(listOf("dump", input.toString(), outDir.toString()))
            assertEquals(0, code)
            assertTrue(out.contains("WROTE"))
            assertEquals(2, Files.list(outDir).count())
        }

        @Test
        fun unknownCommandOrMissingFileFailsWithUsage() {
            val (badCmd, _, err) = capture(listOf("frobnicate"))
            assertNotEquals(0, badCmd)
            assertTrue(err.contains("usage", ignoreCase = true))

            val missing = dir.resolve("nope.bin").toString()
            val (missingCode, _, _) = capture(listOf("list", missing))
            assertNotEquals(0, missingCode)
        }

        private fun buildCliContainer(target: Path) {
            val fs = POIFSFileSystem()
            fs.root.createDocument("RootDoc", ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)))
            val one = fs.root.createDirectory("One")
            one.createDocument("\u0001CompObj", ByteArrayInputStream("type-info".toByteArray()))
            Files.newOutputStream(target).use { fs.writeFilesystem(it) }
            fs.close()
        }
    }

    private fun capture(args: List<String>): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = OleTools.run(args, PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"))
        return Triple(code, out.toString("UTF-8"), err.toString("UTF-8"))
    }

    private fun pngAfterHeader(headerLen: Int): ByteArray =
        ByteArray(headerLen) + PNG_MAGIC + ByteArray(40)

    private companion object {
        val PNG_MAGIC =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    }
}
