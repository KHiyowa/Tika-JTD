package com.hiyowa.tika.jtd.cli

import java.io.IOException
import java.io.OutputStream
import java.io.PrintStream
import org.apache.tika.cli.TikaCLI

/**
 * フル機能 Tika CLI (`tika-app` 互換) のディスパッチャおよび実行ハーネス。
 * [MainKt.main] は本オブジェクトの exit コードで終了する。
 *
 * 標準オプション:
 * - `-t`, `--text`: プレーンテキスト抽出（従来の cat / export --format txt 相当）
 * - `-m`, `--metadata`: メタデータ一覧出力
 * - `-J`, `--jsonRecursive`: 構造化 JSON 出力（従来の export --format json 相当）
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

    private val lock = Any()

    /**
     * コマンド実行の入口。[run] は例外を投げず、exit コードだけ返す。
     * [out] および [err] に出力を流し込み、インプロセスでの高速テスト実行をサポートする。
     */
    fun run(args: List<String>, out: OutputStream, err: OutputStream): Int {
        if (args.isEmpty()) {
            return failUsage(err, "No arguments provided")
        }

        // 単体の help / -h / --help / -? はヘルプ表示（exit 0）
        if (args.size == 1 && (args[0] == "help" || args[0] == "-h" || args[0] == "--help" || args[0] == "-?")) {
            return executeWithStreams(listOf("--help"), out, err)
        }

        return executeWithStreams(args, out, err)
    }

    private fun failUsage(err: OutputStream, message: String?): Int {
        val ps = PrintStream(err, true, "UTF-8")
        if (message != null) {
            ps.println(message)
        }
        executeWithStreams(listOf("--help"), err, err)
        return EXIT_USAGE
    }

    private fun executeWithStreams(args: List<String>, out: OutputStream, err: OutputStream): Int {
        synchronized(lock) {
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
