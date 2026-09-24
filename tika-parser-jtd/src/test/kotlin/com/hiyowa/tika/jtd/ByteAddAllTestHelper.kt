package com.hiyowa.tika.jtd

/**
 * テストヘルパ: [Int] 集合の各要素（0x00..=0xFF の生バイト値）を [MutableList<Byte>] にバイトとして追記する。
 *
 * テストは Rust の `bytes.extend_from_slice(&[0x00, 0x90])` 相当を
 * `bytes.addAll(listOf(0x00, 0x90))` の呼び出しで表現するが、0x90（144）は符号付き
 * [Byte] の値域（-128..=127）に収まらないため、式は `List<Int>` に型推論されて
 * メンバ `addAll(Collection<Byte>)` に適用不能になる。この拡張加重（overload）を
 * テスト側に用意することで、テスト本体を変えずに同じ生バイト追記の意味で型が通る。
 *
 * 要素がすべて符号付き [Byte] の値域に収まる呼出（例 `listOf(0x00, 0x1f)`）は
 * [List<Byte>] に推論され `Collection<Int>` ではないため、引き続きメンバ
 * `addAll(Collection<Byte>)` が選択され意味は一切変わらない。
 */
fun MutableList<Byte>.addAll(units: Collection<Int>) {
    for (unit in units) {
        add(unit.toByte())
    }
}
