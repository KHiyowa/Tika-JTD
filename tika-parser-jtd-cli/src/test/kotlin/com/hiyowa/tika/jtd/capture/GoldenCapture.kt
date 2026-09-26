package com.hiyowa.tika.jtd.capture

import com.hiyowa.tika.jtd.cli.Cli
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * ローカルコーパスから golden（stdout 抽出物 + manifest）を採取する開発ハーネス。
 * 移行元 `OpenJTD/scripts/capture_golden.sh` の Kotlin 版（MIGRATION.md §10.5 手順3・手順4の採取側）。
 *
 * コーパス配下の .jtd/.jtt/.jttc の全ファイルに対して [Cli.run] 経由で
 * `--text <file>` を実行し、Tika 標準のテキスト抽出契約で
 * 採取結果を保存する。[Cli.run] 経由で実行することで「`java -jar` と同一経路の CLI 契約」を golden に保持する
 * （exit コード・stdout バイト・stderr 非保存の3点すべてが CLI 本体の挙動そのものになる）。
 *
 * 出力レイアウト（[outDir] 以下。相対パスの `/` を `_` に置換した写像名）:
 * - `<relpathの/→_>.text.txt`: `--text` の stdout バイト列
 * - `manifest.tsv`: `relpath<TAB>cmd<TAB>exit<TAB>sha256(stdout)` を UTF-8・TAB 区切り・
 *   各行 `\n` 終端で1ファイル1行（cmd=text）
 *
 * stderr バイトはディスクに一切書かない。
 * 採取順序は [manifestRoot] 基準の相対パス文字列の Unicode コードポイント順で固定し、
 * manifest と出力ファイル名の対応が再現可能になる。
 *
 * test sourceSet に置くのは配布 jar を汚さないため（Gradle タスク captureGolden は
 * test runtimeClasspath で起動する）。
 */
object GoldenCapture {

    /** 採取対象のファイル拡張子（小文字完全一致。旧スクリプトの `find -name` も LC_ALL=C で大文字区別）。 */
    private val COLLECTED_EXTENSIONS = setOf("jtd", "jtt", "jttc")

    /** stderr への進捗報告間隔（ファイル数）。数十件単位で出す程度が旧スクリプトと同等。 */
    private const val PROGRESS_EVERY = 50

    /**
     * [corpusDir] 配下のコーパス（.jtd/.jtt/.jttc のみ）を再帰収集し、ファイルごとに
     * `--text` を [Cli.run] で実行して stdout・exit コードを採取する。
     *
     * 収集順序は [manifestRoot] 基準の相対パス（`rel`）の Unicode コードポイント順
     * （`sortedBy { rel }`）。各ファイルは manifest 行 `rel<TAB>text<TAB>exit<TAB>sha256(stdout)` を記録し、最後に
     * [outDir]/manifest.tsv（UTF-8・TAB 区切り・各行 `\n` 終端）として書き出す。
     *
     * @param corpusDir 採取対象のコーパスルートディレクトリ
     * @param outDir golden 出力先ディレクトリ（必要に応じて mkdirs）
     * @param manifestRoot manifest 行の relpath の基準ディレクトリ（通常はリポジトリルート）
     * @return 収集したファイル数（manifest 行数と一致。0 の場合は例外）
     * @throws IllegalArgumentException [corpusDir] がディレクトリでない場合
     * @throws IllegalStateException 収集ファイルが 0 件の場合（旧スクリプトの total==0 → exit 1 相当）
     */
    fun capture(corpusDir: File, outDir: File, manifestRoot: File): Int {
        if (!corpusDir.isDirectory) {
            throw IllegalArgumentException("corpus not found: ${corpusDir.absolutePath}")
        }
        val entries = corpusDir.walkTopDown()
            .filter { it.isFile && it.extension in COLLECTED_EXTENSIONS }
            .map { file -> manifestRoot.toPath().relativize(file.toPath()).toString() to file }
            .sortedBy { it.first }
            .toList()
        if (entries.isEmpty()) {
            throw IllegalStateException(
                "no corpus files captured under ${corpusDir.absolutePath} (no .jtd/.jtt/.jttc found)",
            )
        }
        outDir.mkdirs()
        val manifestRows = ArrayList<String>(entries.size)
        entries.forEachIndexed { index, (rel, file) ->
            val outsuffix = rel.replace('/', '_')
            captureCommand(rel, outsuffix, outDir, "text", file, manifestRows)
            if ((index + 1) % PROGRESS_EVERY == 0) {
                System.err.println("captured ${index + 1}/${entries.size} files")
            }
        }
        File(outDir, "manifest.tsv")
            .writeText(manifestRows.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
        return entries.size
    }

    /**
     * 1 ファイル 1 コマンドの採取。[Cli.run] を呼んで exit コードと stdout バイトを取得し、
     * `<outsuffix>.<cmd>.txt` に書き出してから manifest 行を [rows] に積む。
     * stderr は取得のみでディスクに書かない。
     */
    private fun captureCommand(
        rel: String,
        outsuffix: String,
        outDir: File,
        command: String,
        file: File,
        rows: MutableList<String>,
    ) {
        val args = listOf("--text", file.absolutePath)
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream() // 取得のみ（ディスクに書かない）
        val exit = Cli.run(args, stdout, stderr)
        File(outDir, "$outsuffix.$command.txt").writeBytes(stdout.toByteArray())
        rows.add("$rel\t$command\t$exit\t${sha256Hex(stdout.toByteArray())}")
    }

    /** [MessageDigest] による sha256（旧スクリプトの `shasum -a 256` と同一の16進小文字表記）。 */
    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * [main] 相当のエントリポイント。Gradle タスク `:tika-parser-jtd-cli:captureGolden`
     * （JavaExec・test runtimeClasspath）から起動する。
     *
     * オプショナル引数:
     * - `--corpus <dir>`: コーパスルート（既定 `<root>/testdata/corpus`）
     * - `--out <dir>`: golden 出力先（既定 `<root>/testdata/golden/new`）
     * - `--root <dir>`: リポジトリルート（既定 user.dir）。manifest relpath の基準でもある
     *
     * exit コード: 0 = 成功（収集件数を stdout）、1 = 失敗（メッセージを stderr）。
     *
     * 注意: [object] のメンバ関数は `@JvmStatic` が無いと Java launcher から見える静的
     * `main` にならない（JavaExec/`java` は静的 main を要求する。実測ハマりポイント）。
     */
    @JvmStatic
    fun main(args: Array<String>) {
        var rootDir = File(System.getProperty("user.dir"))
        var corpusDir: File? = null
        var outDir: File? = null
        var index = 0
        while (index < args.size) {
            when (val flag = args[index]) {
                "--corpus", "--out", "--root" -> {
                    index++
                    val value = args.getOrNull(index)
                        ?: exitWithFailure("missing value for `$flag`")
                    when (flag) {
                        "--corpus" -> corpusDir = File(value)
                        "--out" -> outDir = File(value)
                        else -> rootDir = File(value)
                    }
                }
                else -> exitWithFailure("unknown argument: `$flag` (expected --corpus/--out/--root)")
            }
            index++
        }
        val root = rootDir
        val corpus = corpusDir ?: File(root, "testdata/corpus")
        val out = outDir ?: File(root, "testdata/golden/new")
        val count = try {
            capture(corpus, out, root)
        } catch (e: Exception) {
            exitWithFailure(e.message ?: e.javaClass.name)
        }
        println(count)
    }

    /** 失敗出口: メッセージを stderr に書いて exit 1。 */
    private fun exitWithFailure(message: String): Nothing {
        System.err.println(message)
        exitProcess(1)
    }
}
