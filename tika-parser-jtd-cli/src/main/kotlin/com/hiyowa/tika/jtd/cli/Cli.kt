package com.hiyowa.tika.jtd.cli

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import org.apache.tika.cli.TikaCLI
import org.apache.tika.config.TikaExtras
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.parser.Parser
import org.apache.tika.sax.BodyContentHandler

/**
 * フル機能 Tika CLI (`tika-app` 互換) のディスパッチャおよび実行ハーネス。
 * [MainKt.main] は本オブジェクトの exit コードで終了する。
 *
 * 標準オプション:
 * - `-t`, `--text`: プレーンテキスト抽出（スレッドセーフな直接高速パス）
 * - `-m`, `--metadata`: メタデータ一覧出力
 * - `-J`, `--jsonRecursive`: 構造化 JSON 出力
 * - `-x`, `--xml`: XHTML 出力
 * - `-h`, `--help`: ヘルプ表示（exit 0）
 *
 * exit コード:
 * - 0: 成功（broken pipe 含む）、ヘルプ表示
 * - 1: 読み込み・解析失敗、エラー
 * - 2: usage エラー（無引数時など）
 */
object Cli {

    private const val EXIT_OK = 0
    private const val EXIT_FAILURE = 1
    private const val EXIT_USAGE = 2

    /**
     * 共有 AutoDetectParser（スレッドセーフ）。
     * Tika 4.0 のパーサーおよび検出器はスレッドセーフに設計されており、
     * 複数スレッドから同時に並行パースを実行可能。
     */
    private val sharedParser: Parser by lazy {
        TikaExtras.install()
        AutoDetectParser()
    }

    private val fallbackLock = Any()

    /**
     * コマンド実行の入口。[run] は例外を投げず、exit コードだけ返す。
     * [out] および [err] に出力を流し込み、インプロセスでの高速並列テスト実行をサポートする。
     */
    fun run(args: List<String>, out: OutputStream, err: OutputStream): Int {
        if (args.isEmpty()) {
            return failUsage(err, "No arguments provided")
        }

        // 単体の help / -h / --help / -? はヘルプ表示（exit 0）
        if (args.size == 1 && (args[0] == "help" || args[0] == "-h" || args[0] == "--help" || args[0] == "-?")) {
            return executeFallback(listOf("--help"), out, err)
        }

        // 高速・スレッドセーフな直接テキスト抽出パス (--text / -t <file>)
        // System.setOut の差し替えを行わず、指定の OutputStream へ直接ストリーム出力するため完全スレッドセーフ。
        if (args.size == 2 && (args[0] == "--text" || args[0] == "-t")) {
            return runTextExtract(args[1], out, err)
        }

        return executeFallback(args, out, err)
    }

    private fun runTextExtract(filePath: String, out: OutputStream, err: OutputStream): Int {
        val file = File(filePath)
        if (!file.isFile) {
            val ps = PrintStream(err, true, "UTF-8")
            ps.println("File not found: $filePath")
            return EXIT_FAILURE
        }
        return try {
            val metadata = Metadata()
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.name)
            val writer = OutputStreamWriter(out, StandardCharsets.UTF_8)
            val handler = BodyContentHandler(writer)
            val context = ParseContext()
            context.set(Parser::class.java, sharedParser)

            TikaInputStream.get(file.toPath(), metadata).use { stream ->
                sharedParser.parse(stream, handler, metadata, context)
            }
            writer.flush()
            EXIT_OK
        } catch (e: Exception) {
            if (isBrokenPipe(e)) {
                return EXIT_OK
            }
            val ps = PrintStream(err, true, "UTF-8")
            ps.println(e.message ?: e.javaClass.name)
            EXIT_FAILURE
        }
    }

    private fun failUsage(err: OutputStream, message: String?): Int {
        val ps = PrintStream(err, true, "UTF-8")
        if (message != null) {
            ps.println(message)
        }
        executeFallback(listOf("--help"), err, err)
        return EXIT_USAGE
    }

    private fun executeFallback(args: List<String>, out: OutputStream, err: OutputStream): Int {
        synchronized(fallbackLock) {
            val originalOut = System.out
            val originalErr = System.err
            val printOut = PrintStream(out, true, "UTF-8")
            val printErr = PrintStream(err, true, "UTF-8")
            try {
                System.setOut(printOut)
                System.setErr(printErr)
                val normalizedArgs = args.map { if (it == "help") "--help" else it }.toTypedArray()
                TikaCLI.main(normalizedArgs)
                printOut.flush()
                printErr.flush()
                return EXIT_OK
            } catch (e: Exception) {
                if (isBrokenPipe(e)) {
                    return EXIT_OK
                }
                val msg = e.message ?: e.javaClass.name
                printErr.println(msg)
                printErr.flush()
                return EXIT_FAILURE
            } finally {
                System.setOut(originalOut)
                System.setErr(originalErr)
            }
        }
    }

    private fun isBrokenPipe(t: Throwable): Boolean {
        var curr: Throwable? = t
        while (curr != null) {
            if (curr is IOException && (curr.message?.contains("Broken pipe", ignoreCase = true) == true)) {
                return true
            }
            curr = curr.cause
        }
        return false
    }
}
