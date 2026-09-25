package com.hiyowa.tika.jtd.capture

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [GoldenCapture]（旧 `OpenJTD/scripts/capture_golden.sh` の Kotlin 版・MIGRATION.md §10.5 手順3）の
 * レイアウト契約テスト。
 *
 * テストフィクスチャには意図的にダミーバイト列（不正な JTD ファイル）を使用（パース失敗 → exit 1・stdout 空）。
 * 実データの収集体・成否そのものは opt-in の実データレール（corpus 必須）に任せ、
 * 本テストでは **収集レイアウト・並び順・manifest 整形・拡張子フィルタ** だけを固定する。
 */
class GoldenCaptureTest {

    /** 空バイト列の sha256（失敗時の stdout 契約）。 */
    private val shaEmpty = MessageDigest.getInstance("SHA-256")
        .digest(ByteArray(0))
        .joinToString("") { "%02x".format(it) }

    private fun newTempRoot(name: String): File {
        val root = File(System.getProperty("java.io.tmpdir"), "golden-capture-test-$name-${System.nanoTime()}")
        root.deleteRecursively()
        assertTrue(root.mkdirs())
        return root
    }

    private fun touch(file: File, content: ByteArray) {
        file.parentFile.mkdirs()
        file.writeBytes(content)
    }

    @Test
    fun manifestRowOrderIsRelPathByteSortWithExportBeforeCat() {
        val root = newTempRoot("order")
        val corpus = File(root, "testdata/corpus")
        touch(File(corpus, "z.jtd"), byteArrayOf(1, 2, 3))
        touch(File(corpus, "文書.jtd"), byteArrayOf(4, 5))
        touch(File(corpus, "sub/first.jttc"), byteArrayOf(6))
        touch(File(corpus, "notes.txt"), byteArrayOf(7)) // 拡張子フィルタで除外
        touch(File(corpus, "guide.pdf"), byteArrayOf(8)) // 同上
        val out = File(root, "testdata/golden/new")

        val count = GoldenCapture.capture(corpus, out, root)

        assertEquals(3, count, "収集対象は .jtd/.jtt/.jttc のみ（1 ファイル 2 行）")
        val rows = File(out, "manifest.tsv").readText(Charsets.UTF_8).trimEnd('\n').split('\n')
        assertEquals(6, rows.size)
        val expectedRels = listOf(
            "testdata/corpus/sub/first.jttc",
            "testdata/corpus/z.jtd",
            "testdata/corpus/文書.jtd",
        )
        val actual = rows.map { it.split('\t') }
        for ((i, rel) in expectedRels.withIndex()) {
            assertEquals(rel, actual[2 * i][0], "relpath は manifestRoot 基準・UTF-8 バイト順")
            assertEquals("export", actual[2 * i][1], "同一ファイルは export 行が先")
            assertEquals(rel, actual[2 * i + 1][0])
            assertEquals("cat", actual[2 * i + 1][1])
        }
    }

    @Test
    fun outputFilesMirrorRelPathWithUnderscoreAndManifestCarriesExitAndSha() {
        val root = newTempRoot("layout")
        val corpus = File(root, "testdata/corpus")
        touch(File(corpus, "multi-sheet/broken.jtd"), byteArrayOf(0, 1, 2, 3))
        val out = File(root, "testdata/golden/new")

        GoldenCapture.capture(corpus, out, root)

        val base = "testdata_corpus_multi-sheet_broken.jtd"
        val cat = File(out, "$base.cat.txt")
        val export = File(out, "$base.export.txt")
        assertTrue(cat.isFile, "cat 出力ファイル（/ → _ の写像名）")
        assertTrue(export.isFile, "export 出力ファイル")
        // 不正ファイル（パース失敗）時は exit 1・stdout 空（rjtd/Kotlin CLI 共通の失敗契約）
        assertContentEquals(ByteArray(0), cat.readBytes())
        assertContentEquals(ByteArray(0), export.readBytes())

        val rows = File(out, "manifest.tsv").readLines(Charsets.UTF_8).map { it.split('\t') }
        assertEquals(2, rows.size)
        for ((row, cmd) in rows.zip(listOf("export", "cat"))) {
            assertEquals("testdata/corpus/multi-sheet/broken.jtd", row[0])
            assertEquals(cmd, row[1])
            assertEquals("1", row[2])
            assertEquals(shaEmpty, row[3])
        }
    }

    @Test
    fun captureFailsFastWhenCorpusMissing() {
        val root = newTempRoot("missing")
        val out = File(root, "testdata/golden/new")
        val error = runCatching { GoldenCapture.capture(File(root, "nowhere"), out, root) }.exceptionOrNull()
        assertTrue(error != null && error.message!!.contains("corpus"), "コーパス不在は明確なエラー")
    }
}
