package com.hiyowa.tika.jtd

/**
 * /LayoutBoxText（囲み枠テキスト）ストリームのレコード reader（P2 仕様）。
 * 移植元: OpenJTD `rjtd-core/src/layout_box_text.rs`（+ `layout_box_text/span_decode.rs`）。
 *
 * 観測レイアウト（実測レイアウト・LayoutBoxTextReaderTest の
 * 合成フィクスチャと同一構造、word 単位）:
 * ```text
 * w[0..9]        SsmgV.01 ヘッダ（w[9] = ブロック数）
 * w[10 + 128*k]（byte 20 + 256*k）に各ブロック:
 *   "TextV.01"（4語） + [0x0000, span 長（語数）]（2語） + span 内容 + 0x0000 パディング
 *   （ブロック語ピッチ 128 word = 256 byte）
 * ストリーム末尾: 位置/所有テーブル列（dword 群、'c'(0x000063)・'I'(0x000049) 等の
 *   printable な語を含む）。テキスト復元には絶対に読めない。
 * ```
 *
 * span 内容の種別:
 * - 最初のマーカー（0x001c/0x001d/0x001f）より手前の raw 前置きテキスト
 * - 0x001c…0x001f レコード（本体に座標等の printable バイナリ語 0x00be/0x020d を含み、
 *   必ずスキップする）
 * - 0x001d…0x001e インラインテキスト（テキストのみ抽出する）
 * - マーカー無しブロックは全文 raw（0x000a 改行保持・末尾 0x0000 パディング除去）
 *
 * 復元テキストの cat 出力契約（JtdParser が本文後に追記する側で利用）:
 * ブロック毎に（前置き text ＋ インライン/run text を出現順で連結、前置き内の
 * CR/LF は保持）、ブロック間を '\n' で連結。
 *
 * 読取領域はブロック数（w[9]）とピッチで厳密に区切り、末尾テーブル列は読まない。
 * span 復元は P1 のプロローグ raw デコード機構（[DocumentTextParser.decodePrologueUnits] /
 * [DocumentTextParser.firstTextMarker]）と通常のマーカー走査
 * （[DocumentTextParser.parseDocumentText]）を再利用する。
 */
object LayoutBoxTextReader {

    /** /LayoutBoxText ストリームの CFB フルパス（Rust `LAYOUT_BOX_TEXT_PATH`）。 */
    const val LAYOUT_BOX_TEXT_PATH: String = "/LayoutBoxText"

    // 移植元 Rust の定数を同一値で維持。
    private val LAYOUT_BOX_MAGIC: ByteArray = "SsmgV.01".toByteArray(Charsets.ISO_8859_1)

    // "TextV.01" を UTF-16BE word 列で表したセグメント名
    private val TEXT_SEGMENT_NAME: IntArray = intArrayOf(0x5465, 0x7874, 0x562E, 0x3031)

    private const val LAYOUT_BOX_HEADER_WORDS = 10 // SsmgV.01 (4) + header (4) + block-count (2)
    private const val TEXT_SEGMENT_NAME_WORDS = 4
    private const val LAYOUT_BOX_SPAN_HEADER_WORDS = 2 // [0x0000, span 長]
    private const val LAYOUT_BOX_BLOCK_PITCH_WORDS = 128 // 256 byte

    /** ブロック1個あたりの span 内容の最大語数（128 - 4 - 2）。Rust `LAYOUT_BOX_MAX_SPAN_WORDS`。 */
    const val LAYOUT_BOX_MAX_SPAN_WORDS = 122 // 128 - 4 - 2

    /**
     * /LayoutBoxText 内の復元テキスト（ブロックを '\n' で連結して取得する）。
     * Rust `LayoutBoxText` と同一の保持体。
     */
    class LayoutBoxText(val blocks: List<String>) {
        /** 復元テキスト全体をブロック間に改行を挟んで返す（ブロック内は前置き＋インライン/run の出現順連結）。 */
        fun text(): String = blocks.joinToString("\n")
    }

    /**
     * CFB コンテナ [data] から /LayoutBoxText ストリームを読み出し、[parseLayoutBoxText] に委譲する。
     *
     * /LayoutBoxText 欠落・読み取り失敗・解析失敗はすべて null
     * （呼び出し側は本文のみの従来出力を維持する）。
     */
    fun readLayoutBoxText(data: ByteArray): LayoutBoxText? =
        try {
            val stream = JtdContainerReader.withFileSystem(data) { fs ->
                JtdContainerReader.readStream(fs, LAYOUT_BOX_TEXT_PATH)
            } ?: return null
            parseLayoutBoxText(stream)
        } catch (e: Exception) {
            // 読取失敗（破損・IO 等）はレイアウト不符と同等扱いで null。
            null
        }

    /**
     * /LayoutBoxText ペイロード（ストリーム全体）を解析する（Rust `parse_layout_box_text`、
     * 84〜132行の忠実移植）。
     *
     * SsmgV.01 マジック + 語数>=10 のゲートの後、w[9]（ブロック数）で領域を区切る。
     * ブロック数が 0 は空の [LayoutBoxText]（null ではない）。ブロック領域の宣言分が
     * 欠ける（切り詰め等）・ブロック先頭の "TextV.01" セグメント名不一致・
     * span 長がブロックピッチ内最大（[LAYOUT_BOX_MAX_SPAN_WORDS] 語）を超える入力は
     * null（cat は本文のみを出力し続ける）。
     */
    fun parseLayoutBoxText(data: ByteArray): LayoutBoxText? {
        if (!data.startsWith(LAYOUT_BOX_MAGIC) || data.size < LAYOUT_BOX_HEADER_WORDS * 2) {
            return null
        }

        // Rust の chunks_exact(2) と同一挙動: 末尾の奇数バイトは破棄。
        val units = toUnits(data)

        val blockCount = units.getOrNull(LAYOUT_BOX_HEADER_WORDS - 1) ?: return null
        if (blockCount == 0) {
            return LayoutBoxText(emptyList())
        }

        // ブロック領域は w[9]（ブロック数）とピッチで厳密に区切る（末尾テーブルは読まない）。
        // 宣言分が欠ける（切り詰め等）レイアウトは復元不能とみなす。
        val blockAreaEnd = LAYOUT_BOX_HEADER_WORDS + blockCount * LAYOUT_BOX_BLOCK_PITCH_WORDS
        if (units.size < blockAreaEnd) {
            return null
        }

        val blocks = mutableListOf<String>()
        for (k in 0 until blockCount) {
            val blockUnitStart = LAYOUT_BOX_HEADER_WORDS + k * LAYOUT_BOX_BLOCK_PITCH_WORDS
            if (!isTextv01SegmentName(units, blockUnitStart)) {
                return null
            }
            val spanStart = blockUnitStart + TEXT_SEGMENT_NAME_WORDS + LAYOUT_BOX_SPAN_HEADER_WORDS
            val spanLen = units.getOrNull(spanStart - 1) ?: 0
            if (spanLen > LAYOUT_BOX_MAX_SPAN_WORDS) {
                return null
            }
            blocks.add(decodeLayoutBoxSpan(units, spanStart, spanStart + spanLen))
        }

        return LayoutBoxText(blocks)
    }

    /** [decodeLayoutBoxSpan] の [List] ベースオーバーロード（LayoutBoxTextReaderTest 等利用者向け）。 */
    internal fun decodeLayoutBoxSpan(units: List<Int>, start: Int, end: Int): String =
        decodeLayoutBoxSpan(units.toIntArray(), start, end)

    /**
     * units[start..end) の 1 span 復元（Rust `span_decode::decode_layout_box_span` 相当）。
     *
     * - 最初のマーカー（0x001c/0x001d/0x001f）なし: [DocumentTextParser.decodePrologueUnits]
     *   全文 raw（0x0000 パディング除去・CR/LF 保持）。デコード不能（null）なら空文字列。
     * - マーカー位置 m あり: 前置き [DocumentTextParser.decodePrologueUnits](start..m)
     *   （デコード不能なら空文字列）＋ m..end を BE バイト列化して
     *   [DocumentTextParser.parseDocumentText] の通常マーカー走査で plain-text 化して連結
     *   （reading_text=false で開始するため前置きより後だけ読まれ、レコード本体の
     *   printable バイナリ語は読み込まれない）。
     */
    internal fun decodeLayoutBoxSpan(units: IntArray, start: Int, end: Int): String {
        return when (val marker = DocumentTextParser.firstTextMarker(units, start, end)) {
            null -> DocumentTextParser.decodePrologueUnits(units, start, end)?.text.orEmpty()
            else -> {
                val prologue = DocumentTextParser.decodePrologueUnits(units, start, marker)?.text.orEmpty()
                prologue + DocumentTextParser.parseDocumentText(bytesOfUnits(units, marker, end)).plainText()
            }
        }
    }

    // ブロック先頭に "TextV.01" セグメント名があるか（4語 = 8 byte の完全一致）。
    // 範囲外は false（Rust `is_textv01_segment_name` 対応）。
    private fun isTextv01SegmentName(units: IntArray, offset: Int): Boolean {
        if (offset < 0 || offset + TEXT_SEGMENT_NAME_WORDS > units.size) return false
        for (j in TEXT_SEGMENT_NAME.indices) {
            if (units[offset + j] != TEXT_SEGMENT_NAME[j]) return false
        }
        return true
    }

    // units[from..to) を big-endian バイナリ化（Rust `words_to_be_bytes` 対応）。
    private fun bytesOfUnits(units: IntArray, from: Int, to: Int): ByteArray {
        val bytes = ByteArray((to - from) * 2)
        for (i in from until to) {
            bytes[(i - from) * 2] = (units[i] ushr 8).toByte()
            bytes[(i - from) * 2 + 1] = units[i].toByte()
        }
        return bytes
    }

    // バイト列を big-endian u16 語列に変換（Rust chunks_exact(2) 相当・末尾の奇数バイトは破棄）。
    private fun toUnits(data: ByteArray): IntArray {
        val count = data.size ushr 1
        val units = IntArray(count)
        for (i in 0 until count) {
            units[i] = ((data[i * 2].toInt() and 0xFF) shl 8) or (data[i * 2 + 1].toInt() and 0xFF)
        }
        return units
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
