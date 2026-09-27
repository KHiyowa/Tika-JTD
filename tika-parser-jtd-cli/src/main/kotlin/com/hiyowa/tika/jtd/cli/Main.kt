package com.hiyowa.tika.jtd.cli

import kotlin.system.exitProcess

/**
 * フル機能 Tika CLI (`tika-app` 互換) エントリポイント。実体は [Cli]。
 * exit コード契約: 0 = 成功（broken pipe 含む）、1 = 読み込み・解析失敗、2 = usage エラー
 * （`help` / `-h` / `--help` は必ず 0 を返す）。
 */
fun main(args: Array<String>) {
    exitProcess(Cli.run(args.toList(), System.out, System.err))
}
