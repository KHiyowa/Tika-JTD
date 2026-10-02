package com.hiyowa.tika.jtd.capture

import com.hiyowa.tika.jtd.JtdParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.ToXMLContentHandler

/**
 * ローカルコーパス配下の .jtd/.jtt/.jttc を現行パーサーで XHTML 化して採取する開発ハーネス。
 *
 * [GoldenCapture] と同じ並列採集パターン（[parallelStream] ＋ 1 JVM・ JVM 起動コストゼロ）で、
 * CLI `--xml` と同一の SAX パイプライン（[JtdParser] → XHTML SAX → [org.apache.tika.sax.ToXMLContentHandler]）
 * を直接呼ぶ（CLI の `-x` は TikaCLI フォールバックがロック直列化するため並列化できない。
 * ここでは SAX 契約を維持したまま並列化するためにハンドラを直接使う）。
 *
 * 出力レイアウト（[outDir] 以下。golden と同じ `relpath の / → _` 写像名）:
 * - `<relpath の /→_>.xhtml`: XHTML 本文バイト列（[VIEW_STYLE] の表示 CSS を注入済み・
 *   ブラウザで開くと `<table>` 構造に沿った罫線がそのまま見える）
 * - `manifest.tsv`: `relpath<TAB>xml<TAB>exit<TAB>sha256(stdout)` を UTF-8・TAB 区切り・
 *   各行 `\n` 終端で 1 ファイル 1 行（golden manifest と同一形。cmd=xml。sha は注入後のbytes）
 *
 * stderr バイトはディスクに書かない。採取順は [manifestRoot] 基準相対パスの
 * Unicode コードポイント順で固定（[GoldenCapture] と同一再現性契約）。
 */
object XhtmlCapture {

    private val COLLECTED_EXTENSIONS = setOf("jtd", "jtt", "jttc")

    /** 進行報告間隔（ファイル数）。[AtomicInteger] でスレッドセーフ。 */
    private const val PROGRESS_EVERY = 100

    /**
     * 採取 XHTML に注入する表示 CSS（レビュー用・構造は変えない）。
     *
     * `<table>` タグに沿って全セル罫線を表示し、縦継続セルの `<br/>` 継ぎ目と全角空白の
     * 折返しが行として見えるよう `white-space: pre-wrap` を指定する。パーサー出力の構造契約
     * （RFC 0013 §5）には一切触れず、`<head>` 直前への要素追加のみ行う。
     */
    private const val VIEW_STYLE =
        "<style>\n" +
            "body{font-family:\"Hiragino Kaku Gothic ProN\",\"Yu Gothic\",Meiryo,sans-serif;font-size:12px;margin:12px;}\n" +
            "table{border-collapse:collapse;margin:10px 0;background:#fff;}\n" +
            "td,th{border:1px solid #666;padding:3px 6px;vertical-align:top;white-space:pre-wrap;word-break:break-all;}\n" +
            "th{background:#eee;}\n" +
            "tbody tr:nth-child(even) td{background:#fafafa;}\n" +
            "div.sheet{margin-bottom:24px;border-top:2px solid #333;padding-top:8px;}\n" +
            "h2{font-size:14px;}\n" +
            "</style>\n"

    /**
     * XHTML 文字列へ [VIEW_STYLE] を注入する。`</head>` 直前 > `<head...>` 直後 > `<body>` 直前
     * の順に探索し、いずれにも該当しない場合は先頭に付与する（構造非破壊・冪等ではないため
     * 呼び出しは 1 ファイル 1 回に限定）。
     */
    internal fun injectStyle(html: String): String = when {
        html.contains("</head>") -> html.replaceFirst("</head>", VIEW_STYLE + "</head>")
        html.contains("<head") -> {
            val i = html.indexOf('>', html.indexOf("<head")) + 1
            html.substring(0, i) + VIEW_STYLE + html.substring(i)
        }
        html.contains("<body>") -> html.replaceFirst("<body>", VIEW_STYLE + "<body>")
        else -> VIEW_STYLE + html
    }

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
            throw IllegalStateException("no corpus files captured under ${corpusDir.absolutePath}")
        }
        outDir.mkdirs()

        val done = AtomicInteger(0)

        // スレッドセーフな JtdParser.parse を用いて並列に XHTML を採取する
        val manifestRows = entries.parallelStream().map { (rel, file) ->
            val outsuffix = rel.replace('/', '_')
            val row = captureXhtml(rel, outsuffix, outDir, file)
            val c = done.incrementAndGet()
            if (c % PROGRESS_EVERY == 0 || c == entries.size) {
                System.err.println("xhtml capture: $c/${entries.size}")
            }
            row
        }.toList()

        File(outDir, "manifest.tsv")
            .writeText(manifestRows.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
        return entries.size
    }

    /**
     * 1 ファイルの XHTML 採取。CLI `--xml` と同一 SAX ハンドラ（ToXMLContentHandler）で
     * 直列化を回避し、[Cli] と同じ Metadata（resource name）設定で呼ぶ。
     * 解析失敗は exit=1・stdout 空で manifest に記録する（CLI 契約と同じ失败セマンティクス）。
     */
    private fun captureXhtml(rel: String, outsuffix: String, outDir: File, file: File): String {
        val stdout = ByteArrayOutputStream()
        return try {
            val handler = ToXMLContentHandler()
            val metadata = Metadata()
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.name)
            val data = file.readBytes()
            TikaInputStream.get(ByteArrayInputStream(data)).use { s ->
                JtdParser().parse(s, handler, metadata, ParseContext())
            }
            val bytes = injectStyle(handler.toString()).toByteArray(Charsets.UTF_8)
            stdout.write(bytes)
            File(outDir, "$outsuffix.xhtml").writeBytes(bytes)
            "$rel\txml\t0\t${sha256Hex(bytes)}"
        } catch (e: Exception) {
            System.err.println("capture failed: $rel: ${e.message ?: e.javaClass.name}")
            File(outDir, "$outsuffix.xhtml").writeBytes(ByteArray(0))
            "$rel\txml\t1\t${sha256Hex(ByteArray(0))}"
        }
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

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
        val out = outDir ?: File(root, "build/capture-xhtml")
        val count = try {
            capture(corpus, out, root)
        } catch (e: Exception) {
            exitWithFailure(e.message ?: e.javaClass.name)
        }
        println(count)
    }

    private fun exitWithFailure(message: String): Nothing {
        System.err.println(message)
        exitProcess(1)
    }
}
