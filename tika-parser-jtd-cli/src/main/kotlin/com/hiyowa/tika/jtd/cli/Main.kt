package com.hiyowa.tika.jtd.cli

import kotlin.system.exitProcess

/**
 * CLI エントリポイント。実体は [Cli]（サブコマンド: cat / export / sheets / help）。
 * exit コード契約: 0 = 成功（broken pipe 含む）、1 = 読み込み・解析失敗、2 = usage エラー
 * （`help` / `-h` / `--help` は必ず 0 を返す・Tika checkCommandLine ゲート）。
 */
fun main(args: Array<String>) {
    exitProcess(Cli.run(args.toList(), System.out, System.err))
}
