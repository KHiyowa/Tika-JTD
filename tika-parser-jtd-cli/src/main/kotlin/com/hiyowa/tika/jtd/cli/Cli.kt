package com.hiyowa.tika.jtd.cli

import com.hiyowa.tika.jtd.DocumentTextParser
import com.hiyowa.tika.jtd.FootnoteTextReader
import com.hiyowa.tika.jtd.JtdContainerReader
import com.hiyowa.tika.jtd.JtdException
import com.hiyowa.tika.jtd.JtdFormatDetector
import com.hiyowa.tika.jtd.JtdParser
import com.hiyowa.tika.jtd.ObjectSheetsReader
import com.hiyowa.tika.jtd.ParseLimits
import com.hiyowa.tika.jtd.ResourceLimitException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler

/**
 * tika-parser-jtd-cli のコマンド実体（OpenJTD rjtd-cli slim 契約の移植 + 新 README の
 * `export --format json` 追加）。[MainKt.main] は本オブジェクトの exit コードで終了する。
 *
 * サブコマンド:
 * - `cat <path>`: [JtdParser] を [TikaInputStream] 経由で parse し、`BodyContentHandler(-1)`
 *   のテキストを stdout にバイト正確に書き出す（末尾改行を付与しない）。
 * - `export <path> --format txt|text|json [-s|--sheet <name|index>]`: format 未指定は txt。
 *   json はサードパーティ非依存の最小 JSON（[buildJson]）。非テキストフォーマットは
 *   stderr に "unsupported export format: <fmt>" で exit 2。
 * - `sheets <path>`: 「sheet\t<index>\t<name>\t<storage_path>\t<original_path>」の TSV
 *   （original なしは空欄）。単一シート文書は既定ルート 1 行。
 * - `help` / `-h` / `--help`: usage を stdout に出力して **exit 0**（Tika checkCommandLine
 *   ゲート）。無引数・未知コマンドは usage を stderr に出力して exit 2。
 *
 * exit コード: 0 = 成功（broken pipe 含む・rjtd BROKEN_PIPE_EXIT 相当の無言終了）、
 * 1 = ファイル読み込み・解析失敗（コア例外の message をそのまま stderr）、
 * 2 = usage エラー。
 */
object Cli {

    private const val APP_NAME = "tika-parser-jtd-cli"
    private const val EXIT_OK = 0
    private const val EXIT_FAILURE = 1
    private const val EXIT_USAGE = 2
    private const val READ_BUFFER_SIZE = 8192

    /** [JtdParser] が Metadata に書く JTD 形式キー（パーサー内部の private 定数と同一）。 */
    private const val KEY_JTD_FORMAT = "X-JTD-Format"
    /** JTD 自前 MIME（[JtdParser] が parse で Content-Type として必ず設定する値）。 */
    private const val MIME_JTD = "application/vnd.justsystem.ichitaro"
    /** 既定ルートシート名（[ObjectSheetsReader] の既定値と同一）。 */
    private const val ROOT_SHEET_NAME = "タイトル"

    private val USAGE: String = """
        $APP_NAME

        Kotlin-based Ichitaro (JTD) document engine for Apache Tika.

        Usage:
          $APP_NAME cat <file.jtd>
          $APP_NAME export <file.jtd> [--format <txt|text|json>] [--sheet <name|index>]
          $APP_NAME sheets <file.jtd>
          $APP_NAME help | -h | --help

        Commands:
          cat      Extract document body text to stdout (byte-exact)
          export   Export text (txt|text) or JSON (json, default txt)
          sheets   List sheets as TSV rows
          help     Show this usage

        Options:
          --format, -f <fmt>   Output format for export (txt, text, json)
          --sheet, -s <id>     Select a single sheet by index or name (export)
    """.trimIndent()

    /** stdout が閉じられている（broken pipe）ことを示す内部例外。 */
    private class BrokenPipeException : Exception()

    /**
     * コマンド実行の入口。[run] は一切例外を投げず、exit コードだけ返す。
     */
    fun run(args: List<String>, out: OutputStream, err: OutputStream): Int =
        try {
            execute(args, out, err)
        } catch (e: BrokenPipeException) {
            // rjtd BROKEN_PIPE_EXIT 相当: 無言で成功扱い
            EXIT_OK
        } catch (e: Exception) {
            // 解析エラー（JtdException 等）を含む全未捕捉例外の message をそのまま stderr へ。
            report(err, e.message ?: e.javaClass.name)
            EXIT_FAILURE
        }

    private fun execute(args: List<String>, out: OutputStream, err: OutputStream): Int {
        if (args.isEmpty()) {
            return failUsage(err, null)
        }
        return when (val command = args.first()) {
            "help", "-h", "--help" -> printUsage(out)
            "cat" -> runCat(args.drop(1), out, err)
            "sheets" -> runSheets(args.drop(1), out, err)
            "export" -> runExport(args.drop(1), out, err)
            else -> failUsage(err, "unknown command: $command")
        }
    }

    private fun printUsage(out: OutputStream): Int {
        writeRaw(out, USAGE)
        return EXIT_OK
    }

    private fun failUsage(err: OutputStream, message: String?): Int {
        if (message != null) {
            report(err, message)
        }
        writeRaw(err, USAGE)
        return EXIT_USAGE
    }

    // ------------------------------------------------------------------
    // cat
    // ------------------------------------------------------------------

    private fun runCat(args: List<String>, out: OutputStream, err: OutputStream): Int {
        val path = args.firstOrNull() ?: return failUsage(err, "missing path for `cat`")
        return withDocument(path, err) { data, fileName ->
            // 末尾改行を付与せず、抽出テキストをそのまま出す（rjtd run_cat と同一のバイト契約）。
            writeStdout(out, parseDocumentText(data, fileName))
            EXIT_OK
        }
    }

    // ------------------------------------------------------------------
    // sheets
    // ------------------------------------------------------------------

    private fun runSheets(args: List<String>, out: OutputStream, err: OutputStream): Int {
        val path = args.firstOrNull() ?: return failUsage(err, "missing path for `sheets`")
        return withDocument(path, err) { data, _ ->
            val sheets = ObjectSheetsReader.readDocumentSheets(data)
            // 単一シート文書（/ObjectSheets 不在 → 空リスト）は既定ルート 1 行として扱う。
            val rows = sheets.ifEmpty { listOf(ObjectSheetsReader.SheetItem(0, ROOT_SHEET_NAME, "", null)) }
            val sb = StringBuilder()
            for (sheet in rows) {
                sb.append("sheet\t${sheet.index}\t${sheet.name}\t${sheet.storagePath}\t${sheet.originalPath.orEmpty()}")
                sb.append('\n')
            }
            writeStdout(out, sb.toString())
            EXIT_OK
        }
    }

    // ------------------------------------------------------------------
    // export
    // ------------------------------------------------------------------

    private data class ExportOptions(val format: String, val sheet: String?)

    private fun runExport(args: List<String>, out: OutputStream, err: OutputStream): Int {
        val path = args.firstOrNull() ?: return failUsage(err, "missing path for `export`")
        val options = parseExportOptions(args.drop(1), err) ?: return EXIT_USAGE
        if (options.format != "txt" && options.format != "text" && options.format != "json") {
            report(err, "unsupported export format: ${options.format}")
            return EXIT_USAGE
        }
        return withDocument(path, err) { data, fileName ->
            if (options.format == "json") {
                // 新 README が約束する最小 JSON（サードパーティ非依存・[buildJson]）。
                val selected = options.sheet?.let { resolveSelectedSheet(data, it) }
                val text = if (selected != null) {
                    sheetText(data, selected)
                } else {
                    parseDocumentText(data, fileName)
                }
                val sheets = selected?.let { listOf(it) }
                    ?: ObjectSheetsReader.readDocumentSheets(data).ifEmpty { null }
                val format = JtdFormatDetector.detect(data).asString
                writeStdout(out, buildJson(format, text, sheets) + "\n")
                EXIT_OK
            } else {
                // txt/text エイリアス同一バイト契約（移行レポート 第 6 節⑤）。
                // --sheet 指定時はそのシートのみ出力、未指定時は cat と同一の全文出力。
                val text = options.sheet?.let { sheetText(data, resolveSelectedSheet(data, it)) }
                    ?: parseDocumentText(data, fileName)
                writeStdout(out, text)
                EXIT_OK
            }
        }
    }

    /**
     * export の引数を解析する（rjtd render_support.rs `export_options` と同一構文）。
     * format 未指定は txt（未指定のまま txt を使う点が rjtd と異なるのは本 CLI 契約の明示）。
     * 不正引数は stderr に usage を出して null を返す。
     */
    private fun parseExportOptions(args: List<String>, err: OutputStream): ExportOptions? {
        var format = "txt"
        var sheet: String? = null
        var index = 0
        while (index < args.size) {
            val arg = args[index]
            when (arg) {
                "--format", "-f" -> {
                    index++
                    val value = args.getOrNull(index)
                        ?: return errorUsage(err, "missing value for `$arg`")
                    format = value
                    index++
                }
                "--sheet", "-s" -> {
                    index++
                    val value = args.getOrNull(index)
                        ?: return errorUsage(err, "missing value for `$arg`")
                    sheet = value
                    index++
                }
                else -> {
                    return errorUsage(
                        err,
                        "unexpected export argument `$arg`; usage: $APP_NAME export " +
                            "<file.jtd> [--format <txt|text|json>] [--sheet <name|index>]",
                    )
                }
            }
        }
        return ExportOptions(format, sheet)
    }

    private fun errorUsage(err: OutputStream, message: String): ExportOptions? {
        report(err, message)
        return null
    }

    /**
     * --sheet 指定（index かシートの名前）をシートへ解決する（rjtd render.rs と同一順序:
     * index 解釈 → 名前一致）。単一シート文書は既定ルート 1 シートとして扱う。
     * 見つからない場合は stderr 用メッセージの例外を送出（exit 1 になる）。
     */
    private fun resolveSelectedSheet(
        data: ByteArray,
        spec: String,
    ): ObjectSheetsReader.SheetItem {
        var sheets = ObjectSheetsReader.readDocumentSheets(data)
        if (sheets.isEmpty()) {
            sheets = listOf(ObjectSheetsReader.SheetItem(0, ROOT_SHEET_NAME, "", null))
        }
        val index = spec.toUIntOrNull()
        val sheet = if (index != null) {
            sheets.getOrNull(index.toInt())
        } else {
            sheets.firstOrNull { it.name == spec }
        }
        return sheet ?: throw Exception("sheet `$spec` not found")
    }

    /**
     * 1 シートのエクスポートテキスト（rjtd render.rs のシート分岐と同一:
     * シート本体平文 + 脚注があれば "text.trimEnd() \n\n footnote"）。
     */
    private fun sheetText(data: ByteArray, sheet: ObjectSheetsReader.SheetItem): String {
        val text = sheetBodyText(data, sheet)
        val footnote = FootnoteTextReader.readFootnoteForSheet(data, sheet)
        return if (footnote != null) text.trimEnd() + "\n\n" + footnote else text
    }

    /**
     * シート本体の DocumentText 平文。ルートシート（storagePath 空・"/"）は文書全体の
     * /DocumentText を読み、サブシートは [ObjectSheetsReader.SheetItem.documentTextPath]
     * を読む。ストリーム不在・読取・解析失敗は空文字（model の None 落ちと同一）。
     */
    private fun sheetBodyText(data: ByteArray, sheet: ObjectSheetsReader.SheetItem): String {
        if (sheet.storagePath.isEmpty() || sheet.storagePath == "/") {
            return DocumentTextParser.readDocumentTextPayload(data).text
        }
        return try {
            val bytes = JtdContainerReader.withFileSystem(data) { fs ->
                JtdContainerReader.readStream(fs, sheet.documentTextPath())
            } ?: ByteArray(0)
            DocumentTextParser.parseDocumentText(bytes).plainText()
        } catch (e: Exception) {
            ""
        }
    }

    // ------------------------------------------------------------------
    // 解析・ファイル読み込み
    // ------------------------------------------------------------------

    /**
     * [JtdParser] を [TikaInputStream] 経由で呼び、BodyContentHandler(-1) のテキストを
     * 返す（cat と export の共有経路）。[TikaCoreProperties.RESOURCE_NAME_KEY] にファイル名を
     * 設定し、Tika の拡張子ベース型推測を活かす。
     */
    private fun parseDocumentText(data: ByteArray, fileName: String): String {
        val metadata = Metadata()
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, fileName)
        val handler = BodyContentHandler(-1)
        TikaInputStream.get(data, metadata).use { stream ->
            JtdParser().parse(stream, handler, metadata, ParseContext())
        }
        return handler.toString()
    }

    /**
     * 入力を [parseDocumentText] に渡す前の共通処理: rjtd input.rs 相当のファイル読み込み
     * （存在チェック → 入力サイズ上限 64MiB → 読み込み）を行い、そのバイト列とファイル名で
     * [body] を実行する。読み込み・解析失敗は message をそのまま stderr に出して exit 1。
     */
    private fun withDocument(path: String, err: OutputStream, body: (ByteArray, String) -> Int): Int {
        val data = try {
            readFileLimited(path)
        } catch (e: ResourceLimitException) {
            report(err, e.message ?: "resource limit exceeded")
            return EXIT_FAILURE
        } catch (e: IOException) {
            report(err, "cannot read `$path`: ${e.message ?: e.javaClass.name}")
            return EXIT_FAILURE
        }
        return try {
            body(data, File(path).name)
        } catch (e: BrokenPipeException) {
            throw e
        } catch (e: JtdException) {
            // コア例外の message（"invalid data: ..." 等）をそのまま stderr へ。
            report(err, e.message ?: e.javaClass.name)
            EXIT_FAILURE
        } catch (e: Exception) {
            // シート指定なしの parseDocumentText は parse 経路に伝播するため、
            // 同様に message をそのまま出して exit 1。
            report(err, e.message ?: e.javaClass.name)
            EXIT_FAILURE
        }
    }

    /**
     * rjtd input.rs `read_file_with_limits` の移植: 存在チェック → 64MiB 上限
     * （[ParseLimits.DEFAULT]）→ 上限+1 バイトまで読んだ二重チェック。
     *
     * @throws IOException ファイル不在・I/O 失敗
     * @throws ResourceLimitException 入力サイズ上限超過
     */
    private fun readFileLimited(path: String): ByteArray {
        val file = File(path)
        if (!file.isFile()) {
            throw IOException("cannot open `$path`: file not found")
        }
        ParseLimits.DEFAULT.checkInputSize(file.length())
        val limit = ParseLimits.DEFAULT.maxInputBytes
        return file.inputStream().use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(READ_BUFFER_SIZE)
            var total = 0L
            while (total < limit + 1) {
                val toRead = minOf(buffer.size, (limit + 1 - total).toInt())
                val n = input.read(buffer, 0, toRead)
                if (n <= 0) break
                output.write(buffer, 0, n)
                total += n
            }
            ParseLimits.DEFAULT.checkInputSize(total)
            output.toByteArray()
        }
    }

    // ------------------------------------------------------------------
    // JSON（サードパーティ非依存・最小エスケープ）
    // ------------------------------------------------------------------

    /**
     * 最小 JSON を組み立てる:
     * ```json
     * {"format":"<X-JTD-Format>","contentType":"application/vnd.justsystem.ichitaro",
     *  "sheets":[{"index":N,"name":"..."}...] | null,"text":"..."}
     * ```
     * [sheets] が null のときのみ JSON の `null` を出力する（単一シート文書）。
     */
    private fun buildJson(
        format: String,
        text: String,
        sheets: List<ObjectSheetsReader.SheetItem>?,
    ): String {
        val sb = StringBuilder()
            .append("{\"format\":\"").append(jsonEscape(format)).append('"')
            .append(",\"contentType\":\"").append(jsonEscape(MIME_JTD)).append('"')
            .append(",\"sheets\":")
        if (sheets == null) {
            sb.append("null")
        } else {
            sb.append('[')
            sheets.forEachIndexed { i, sheet ->
                if (i > 0) sb.append(',')
                sb.append("{\"index\":").append(sheet.index).append(",\"name\":\"")
                    .append(jsonEscape(sheet.name)).append("\"}")
            }
            sb.append(']')
        }
        sb.append(",\"text\":\"").append(jsonEscape(text)).append("\"}")
        return sb.toString()
    }

    /**
     * JSON 文字列エスケープの単体実装: `"` / `\` / `\n` / `\r` / `\t` と、
     * それ以外の制御文字（U+0000..U+001F）を `\u00XX` に変換する。
     */
    private fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (ch.code < 0x20) {
                        sb.append("\\u%04X".format(ch.code))
                    } else {
                        sb.append(ch)
                    }
                }
            }
        }
        return sb.toString()
    }

    // ------------------------------------------------------------------
    // 出力ヘルパ
    // ------------------------------------------------------------------

    /**
     * stdout 書き出し（UTF-8）。書き込み失敗（broken pipe 等）は [BrokenPipeException]
     * へ変換し、[run] が exit 0 ・無言として扱う（rjtd support.rs `stdout_error` 相当）。
     */
    private fun writeStdout(out: OutputStream, text: String) {
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
        } catch (e: IOException) {
            throw BrokenPipeException()
        }
    }

    private fun report(err: OutputStream, message: String) {
        writeRaw(err, message + "\n")
    }

    private fun writeRaw(stream: OutputStream, text: String) {
        try {
            stream.write(text.toByteArray(Charsets.UTF_8))
            stream.flush()
        } catch (e: IOException) {
            // エラー出力先の書き込み失敗は報告不能・無視（再帰エラーを避ける）
        }
    }
}
