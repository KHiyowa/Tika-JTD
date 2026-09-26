package com.hiyowa.tika.jtd.capture

import com.hiyowa.tika.jtd.cli.Cli
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * 実データコーパスの sha256 回帰レール（MIGRATION.md §10.5 手順5・opt-in）。
 *
 * コーパス（`testdata/corpus/`・git 未追跡）があるマシンだけで回る追加レールで、
 * `testdata/golden/new/manifest.tsv`（`relpath<TAB>cmd<TAB>exit<TAB>sha256` × 1,204 行）の
 * 全行に対して [Cli.run] を再実行し、**exit コード・stdout sha256・golden ファイル本体**の
 * 三者が一致することを検証する。
 *
 * 有効化: `gradle test -Pgatagate.corpus`（未指定時はスキップ。CI はデータなしで
 * 84 件のみ、手元では本レールで 602 ファイルをSHA固定照合できる）。
 *
 * システムプロパティ（Gradle が -Pgatagate.corpus 時に注入する）:
 * - `gatagate.corpus` = `true`: レール有効化
 * - `gatagate.root`: リポジトリルート（既定 user.dir）
 */
@EnabledIfSystemProperty(named = "gatagate.corpus", matches = "true")
class GoldenManifestRailTest {

    @Test
    fun everyManifestRowReproducesGoldenByteExact() {
        val root = File(System.getProperty("gatagate.root", System.getProperty("user.dir")))
        val golden = File(root, "testdata/golden/new")
        val manifest = File(golden, "manifest.tsv")
        assertTrue(manifest.isFile, "golden manifest が見つからない: ${manifest.path}（先に captureGolden を実行）")

        val rows = manifest.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
        assertTrue(rows.isNotEmpty(), "manifest が空")

        val mismatches = mutableListOf<String>()
        for (row in rows) {
            val (rel, command, expectedExit, expectedSha) = row.split('\t')
            val source = File(root, rel)
            assertTrue(source.isFile, "コーパス入力が見つからない: $rel")
            val args = when (command) {
                "text" -> listOf("--text", source.absolutePath)
                else -> error("unknown cmd in manifest: $command")
            }
            val stdout = ByteArrayOutputStream()
            val exit = Cli.run(args, stdout, ByteArrayOutputStream())
            val sha = MessageDigest.getInstance("SHA-256")
                .digest(stdout.toByteArray())
                .joinToString("") { "%02x".format(it) }
            if (exit.toString() != expectedExit || sha != expectedSha) {
                mismatches.add("$rel $command: exit $exit/$expectedExit sha $sha/$expectedSha")
            }
            val goldenFile = File(golden, rel.replace('/', '_') + ".$command.txt")
            if (!goldenFile.isFile || !goldenFile.readBytes().contentEquals(stdout.toByteArray())) {
                mismatches.add("${goldenFile.name}: golden 本体が再生成結果と不一致")
            }
        }
        assertEquals(emptyList(), mismatches, "golden レール不一致 ${mismatches.size} 件")
    }
}
