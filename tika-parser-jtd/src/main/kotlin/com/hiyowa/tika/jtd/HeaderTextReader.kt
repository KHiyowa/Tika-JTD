package com.hiyowa.tika.jtd

/**
 * /Header（ヘッダ・フッタ本文）ストリームのレコード reader。
 * 移植元: OpenJTD `rjtd-core/src/header_text.rs`（ヘッダ抽出仕様 P-H＋F）。
 *
 * 観測レイアウト（実データ 243 件の機械走査で確定・UTF-16BE word 単位・
 * HeaderTextReaderTest の合成フィクスチャと同一構造）:
 * ```text
 * w[0..9]    SsmgV.01 ヘッダ（w[5] = 0x0001 等・w[9] = セグメント数）
 * w[10] 以降 セグメントスロット列（語ピッチ 128 word = 256 byte）:
 *   "TextV.01"（4語） + [0x0000, span 長（語数）]（2語） + span 内容 + 0x0000 パディング
 *   または空の "TCntV.01"（自動ページ番号連番キャッシュ・常に空 → 読まない）
 * ストリーム末尾: 位置/所有テーブル列（0x001b・0x005d 等の数値語）。テキスト復元には絶対に読めない。
 * ```
 *
 * span 長はスロットピッチ 128 を超えて後続スロットへオーバーフローしうるため、
 * スロット番号の算術では走査せず、w[10] 以降を "TextV.01" マジック 4 語出現位置で走査する。
 *
 * span 内容の種別:
 * - raw（0x001c/0x001d/0x001f マーカー無し）: 全文 UTF-16BE 生
 * （0x0000 パディングスキップ・CR/LF 保持・制御境界で打ち切り）
 * - マーカー型: 先頭語 0x001c（直後にスタイル語ジャンク）+ 0x001f run マーカーの後ろに
 *   run 本文（末尾に 0x000a が典型的）
 *
 * 復元テキストの cat / export-txt 出力契約（Tika 準拠・[JtdParser] が本文前に先頭置する側で利用）:
 * span 1 個 = 1 行（末尾 CR/LF 除去済み）、ストリーム出現順で各行を先頭行として本文の前に置き、
 * 本文と空行 1 行で区切る。
 * F 裁定（slim/text-only）: raw 経路のデコード結果に限り `?` をページ番号 "1"（先頭ページ）に
 * 解決する。プレースホルダ `?` を含む raw span のデコード結果は観測上 `"- ? -"` のみで誤変換リスクなし。
 *
 * span 復元は P1 のプロローグ raw デコード機構（[DocumentTextParser.decodePrologueUnits] /
 * [DocumentTextParser.firstTextMarker]）と通常のマーカー走査（[DocumentTextParser.parseDocumentText]）
 * を再利用する。
 */
object HeaderTextReader {

    /** /Header ストリームの CFB フルパス（Rust `HEADER_TEXT_PATH`）。 */
    const val HEADER_TEXT_PATH: String = "/Header"

    // 移植元 Rust の定数を同一値で維持
    private val HEADER_MAGIC: ByteArray = "SsmgV.01".toByteArray(Charsets.ISO_8859_1)

    // "TextV.01" を UTF-16BE word 列で表したセグメント名
    private val TEXT_SEGMENT_NAME: IntArray = intArrayOf(0x5465, 0x7874, 0x562E, 0x3031)

    private const val HEADER_COUNT_WORDS = 10 // SsmgV.01 (4) + header (4) + slot-count (2)
    private const val TEXT_SEGMENT_NAME_WORDS = 4
    private const val HEADER_SLOT_HEAD_WORDS = 2 // [0x0000, span 長]

    /**
     * /Header 内の復元テキスト（span をストリーム順に 1 行ずつ取得する）。
     * Rust `HeaderText` と同一の保持体。
     */
    class HeaderText(val lines: List<String>) {
        /** 復元テキスト全体を行間に改行を挟んで返す（末尾改行なし）。 */
        fun text(): String = lines.joinToString("\n")
    }

    /**
     * CFB コンテナ [data] から /Header ストリームを読み出し、[parseHeaderText] に委譲する。
     *
     * /Header 欠落（[NotFoundException] 相当）→ null。読み取り失敗・レイアウト不符・
     * 復元行が 1 行も無い場合 → null（呼び出し側は本文のみの従来出力を維持する）。
     */
    fun readHeaderText(data: ByteArray): HeaderText? =
        try {
            val stream = JtdContainerReader.withFileSystem(data) { fs ->
                JtdContainerReader.readStream(fs, HEADER_TEXT_PATH)
            } ?: return null
            parseHeaderText(stream)
        } catch (e: JtdException) {
            // /Header 読取失敗（破損・IO 等）はレイアウト不符と同等扱いで null。
            null
        }

    /**
     * /Header ペイロード（ストリーム全体）を解析する（Rust `parse_header_text` の純粋関数部対応）。
     *
     * レイアウト（SsmgV.01 ヘッダ + w[10] 以降の TextV.01 スロット列 + 末尾テーブル）の観測構造に
     * 合わない入力・span 長がストリーム末尾を超える読み取り失敗・復元行が 1 行も無い場合は null。
     */
    fun parseHeaderText(data: ByteArray): HeaderText? {
        if (!data.startsWith(HEADER_MAGIC) || data.size < HEADER_COUNT_WORDS * 2) {
            return null
        }

        // Rust の chunks_exact(2) と同一挙動: 末尾の奇数バイトは破棄。
        val units = toUnits(data)

        // w[10] 以降を "TextV.01" マジック 4 語出現位置で走査する（span 長はスロットピッチ 128
        // を超えて後続へオーバーフローしうるため、スロット番号の算術は使わない）。
        val lines = mutableListOf<String>()
        var offset = HEADER_COUNT_WORDS
        while (true) {
            val hit = nextTextv01(units, offset) ?: return finishLines(lines)
            // スロット先行語（hit+4）が 0x0000 で無ければ異常語の混入とみなし、
            // そのヒットだけをスキップして次を探す。
            val slotHead = units.getOrNull(hit + 4) ?: return null
            if (slotHead != 0x0000) {
                offset = hit + 1
                continue
            }
            val spanLenWord = units.getOrNull(hit + 5) ?: return null
            val spanLen = spanLenWord
            if (spanLen == 0) {
                // 空 span（宣言のみ）は復元行にならない。
                offset = hit + 1
                continue
            }
            val spanStart = hit + TEXT_SEGMENT_NAME_WORDS + HEADER_SLOT_HEAD_WORDS
            if (spanStart + spanLen > units.size) {
                // 宣言分が欠ける（切詰め等）span は復元不能。
                return null
            }
            val line = decodeHeaderSpan(units, spanStart, spanStart + spanLen)
            if (line != null) {
                lines.add(line)
            }
            offset = spanStart + spanLen
        }
    }

    private fun finishLines(lines: MutableList<String>): HeaderText? =
        if (lines.isEmpty()) null else HeaderText(lines)

    // 1 span の復元: raw 経路（P1 のプロローグ raw デコード。この経路の出力に限り
    // `?` をページ番号 "1" に解決）かマーカー経路（0x1f run マーカー以降の本文のみ）
    // で取得し、末尾 CR/LF を除去して 1 行化する（末尾の空白・全角スペースは保持）。
    // 除去後に空・空白のみなら null（行として丢弃する）。
    private fun decodeHeaderSpan(units: IntArray, start: Int, end: Int): String? {
        val text = when (val marker = DocumentTextParser.firstTextMarker(units, start, end)) {
            null -> {
                // raw span: 0x0000 パディングスキップ・CR/LF 保持・制御境界で打ち切り。
                val raw = DocumentTextParser.decodePrologueUnits(units, start, end)?.text ?: return null
                // F 裁定: プレースホルダ `?` はプレースホルダ `?` を含む span の
                // デコード結果が `"- ? -"` のみと観測されるため、先頭ページ "1" に解決する。
                raw.replace('?', '1')
            }
            else -> {
                // マーカー型 span: 0x1f run マーカー以降の本文のみを共用の通常マーカー
                // 走査で取得（マーカーより手前のスタイル語ジャンクは絶対読まない）。
                DocumentTextParser.parseDocumentText(bytesOfUnits(units, marker, end)).plainText()
            }
        }
        return toHeaderLine(text)
    }

    // 末尾 CR/LF を繰り返し除去して 1 行化する（span 1 個 = 1 行）。
    // 除去後に trim が空なら null を返す。
    private fun toHeaderLine(text: String): String? {
        var line = text
        while (line.isNotEmpty() && (line.endsWith("\r") || line.endsWith("\n"))) {
            line = line.dropLast(1)
        }
        return if (line.trim().isEmpty()) null else line
    }

    // [from] 以降で "TextV.01" マジック 4 語が初めて出現する位置（Rust `next_textv01` 対応）。
    private fun nextTextv01(units: IntArray, from: Int): Int? {
        for (hit in from until units.size - TEXT_SEGMENT_NAME_WORDS + 1) {
            var match = true
            for (j in TEXT_SEGMENT_NAME.indices) {
                if (units[hit + j] != TEXT_SEGMENT_NAME[j]) {
                    match = false
                    break
                }
            }
            if (match) return hit
        }
        return null
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
