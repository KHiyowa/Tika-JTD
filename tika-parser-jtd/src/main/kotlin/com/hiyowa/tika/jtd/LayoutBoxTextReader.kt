package com.hiyowa.tika.jtd

/**
 * /LayoutBoxText（囲み枠テキスト）ストリームのレコード reader（P2 仕様）。
 * 移植元: OpenJTD `rjtd-core/src/layout_box_text.rs`（+ `layout_box_text/span_decode.rs`）。
 *
 * 観測レイアウト（実測レイアウト・LayoutBoxTextReaderTest の
 * 合成フィクスチャと同一構造、word 単位）:
 * ```text
 * w[0..9]        SsmgV.01 ヘッダ（w[9] = ブロック数）
 * w[10 + 128*k]（byte 20 + 256*k）境界上に各ブロック（可変ピッチ・複数スロット跨ぎあり）:
 *   "TextV.01"（4語） + [0x0000, span 長（語数）]（2語） + span 内容 + 0x0000 パディング
 *   （スロット語ピッチ 128 word = 256 byte。長 span は複数スロットを消費する）
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
 * 読取領域は w[9] を上限端（ブロック領域の端）として区切り、末尾テーブル列は読まない。
 * span は宣言長厳守で読むため、span 末尾に残る非ゼロのレコード尾部語は混入しない。
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
     * /LayoutBoxText ペイロード（ストリーム全体）を解析する（Rust `parse_layout_box_text`
     * 由来・可変ピッチ対応のため逐次追進方式に変更）。
     *
     * SsmgV.01 マジック + 語数>=10 のゲートの後、w[9] をブロック領域の上限端として
     * 領域を区切る（10 + w[9]*128 がストリーム側に存在しなければ null＝切り詰め）。
     * ブロックは p=10 から逐次追進: "TextV.01" + [0x0000, span 長] + span を宣言長厳守で
     * 復元し、span 終端より後ろの次の 128 語境界からセグメント名のあるスロットまで
     * 128 語刻みで追進する（可変ピッチ・複数スロット跨ぎ対応。span 長は 1 スロット
     * 上限 [LAYOUT_BOX_MAX_SPAN_WORDS] 語を超えてよい）。
     * ブロック数が 0 は空の [LayoutBoxText]（null ではない）。先頭ブロックの
     * "TextV.01" セグメント名不一致（構造崩壊）・span の領域超過（切り詰め）は null
     * （cat は本文のみを出力し続ける）。追進先で名前が見つからなければ打ち切り、
     * 集約済みのブロックを返す。
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

        // w[9] はブロック領域の上限端（固定ピッチならブロック数そのもの、可変ピッチの
        // 長 span レイアウトでは領域上限端の宣言）。宣言分が欠ける（切り詰め等）は null。
        val blockAreaEnd = LAYOUT_BOX_HEADER_WORDS + blockCount * LAYOUT_BOX_BLOCK_PITCH_WORDS
        if (units.size < blockAreaEnd) {
            return null
        }

        // 逐次追進方式: span 終端より後ろの次の 128 語境界から、セグメント名を持つ
        // スロット（可変ピッチ・複数スロット跨ぎあり）順にブロックを復元する。
        val blocks = mutableListOf<String>()
        var p = LAYOUT_BOX_HEADER_WORDS
        while (p < blockAreaEnd) {
            if (!isTextv01SegmentName(units, p)) {
                // 先頭ブロック不在は構造崩壊（null）、以降の未発見は正常打ち切り。
                return if (blocks.isEmpty()) null else LayoutBoxText(blocks)
            }
            val spanStart = p + TEXT_SEGMENT_NAME_WORDS + LAYOUT_BOX_SPAN_HEADER_WORDS
            val spanLen = units.getOrNull(spanStart - 1) ?: return null
            val spanEnd = spanStart + spanLen
            // 宣言長厳守。span が領域上限端・ストリーム末尾を超える切り詰めは null。
            if (spanEnd > blockAreaEnd || spanEnd > units.size) {
                return null
            }
            blocks.add(decodeLayoutBoxSpan(units, spanStart, spanEnd))
            // span 終端より後ろの次の 128 語境界へ追進し、セグメント名のあるスロットまで
            // 128 語刻みで探索する（パディング・レコード尾部語は決して読まない）。
            var next = LAYOUT_BOX_HEADER_WORDS + ceilToPitch(spanEnd - LAYOUT_BOX_HEADER_WORDS)
            while (next < blockAreaEnd && !isTextv01SegmentName(units, next)) {
                next += LAYOUT_BOX_BLOCK_PITCH_WORDS
            }
            p = next
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

    // 128 語ピッチへの切り上げ（ピッチ境界算出用・必ず正の整数）。
    private fun ceilToPitch(words: Int): Int {
        val pitch = LAYOUT_BOX_BLOCK_PITCH_WORDS
        return ((words + pitch - 1) / pitch) * pitch
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
