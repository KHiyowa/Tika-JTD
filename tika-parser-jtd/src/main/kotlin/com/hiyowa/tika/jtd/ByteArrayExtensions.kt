package com.hiyowa.tika.jtd

/**
 * バイト列の先頭が [prefix] か。引数が空なら常に真。
 *
 * Kotlin 標準ライブラリに `ByteArray.startsWith` は存在しないため、パッケージ内の
 * 共有ヘルパとして定義する（[DocumentTextPayload.bytes] 等的呼び出しから利用する）。
 */
internal fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
