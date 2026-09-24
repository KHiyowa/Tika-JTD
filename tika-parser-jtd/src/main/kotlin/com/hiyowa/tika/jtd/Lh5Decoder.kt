package com.hiyowa.tika.jtd

import java.io.ByteArrayOutputStream

/**
 * -lh5- (LH5) 圧縮法のデコーダ。OpenJTD rjtd-core/src/lha.rs の完全移植。
 *
 * Rust と Kotlin の整数型が 64-bit で一致するため、lha.rs 中の算術（usize）はそのまま
 * Kotlin の Long 演算に置き換えられる（wrapping 挙動も 64-bit で同一）。
 * MSB-first ビット読み込み、canonical ハフマン、LH5_DICBIT=13 リングバッファは
 * 行番号をコメントで引用しながら忠実に移植する。
 *
 * ビット単位の互換性は生命線のため、テストヘルパ Lh5DecoderTest.bits の MSB-first
 * 詰め込み（8-bit 0 埋め）と、ここ MSB-first BitReader（lha.rs 326-338行）の
 * ビット抽出 `(byte >> (7 - bit_pos)) & 1` が一致することを保証する。
 */
object Lh5Decoder {

    // ===== lha.rs 3-9行 =====

    /** lha.rs:3 の LH5_METHOD (b"-lh5-")。 */
    private val LH5_METHOD: ByteArray = "-lh5-".toByteArray(Charsets.ISO_8859_1)

    /** lha.rs:4 の LH5_DICBIT。 */
    private const val LH5_DICBIT: Int = 13

    /** lha.rs:5 の LH5_DICSIZ。 */
    private const val LH5_DICSIZ: Int = 1 shl LH5_DICBIT

    /** lha.rs:6 の LH5_NP。 */
    private const val LH5_NP: Int = LH5_DICBIT + 1

    /** lha.rs:7 の LH5_NT。 */
    private const val LH5_NT: Int = 19

    /** lha.rs:8 の LH5_NC。 */
    private const val LH5_NC: Int = 510

    /** lha.rs:9 の LH5_THRESHOLD_BASE。 */
    private const val LH5_THRESHOLD_BASE: Long = 253L

    // ===== 公開エントリ =====

    /**
     * デフォルト上限で 1 メンバを展開する。
     * lha.rs:41-43 の decompress_lh5_member。
     */
    fun decompressLh5Member(data: ByteArray): LhaMember =
        decompressLh5MemberWithLimits(data, ParseLimits.DEFAULT)

    /**
     * 明示的な [ParseLimits] で 1 メンバを展開する。
     *
     * lha.rs:45-52 の decompress_lh5_member_with_limits。
     * per-member 出力上限はこのメンバに適用され、新規作成した total budget も
     * この呼び出しのスコープに留まる。入力上限は呼び出し側が data を確保した後に
     * data をチェックする（lha.rs:45-52 の doc コメントと同義）。
     */
    fun decompressLh5MemberWithLimits(data: ByteArray, limits: ParseLimits): LhaMember {
        val budget = limits.decompressionBudget() // lha.rs:50
        return decompressLh5MemberWithBudget(data, budget) // lha.rs:51
    }

    /**
     * 共有 [DecompressionBudget] で 1 メンバを展開する。
     * lha.rs:54-80 の decompress_lh5_member_with_budget の完全移植。
     */
    fun decompressLh5MemberWithBudget(data: ByteArray, budget: DecompressionBudget): LhaMember {
        // lha.rs:58
        budget.checkInputSize(data.size.toLong())

        // lha.rs:59
        val header = parseLhaHeader(data)

        // lha.rs:60-63: packed_end = data_start.checked_add(packed_size)
        // Rust の usize は 64-bit wrapping だが、本移植では overflow 時に InvalidData とする
        // （lha.rs:63 の ok_or_else と同一）。
        val packedEnd: Long = if (header.dataStart > Long.MAX_VALUE - header.packedSize) {
            throw InvalidDataException("LHA packed size overflow") // lha.rs:63
        } else {
            header.dataStart + header.packedSize
        }
        if (packedEnd > data.size.toLong()) { // lha.rs:64
            throw InvalidDataException( // lha.rs:65-70
                "LHA packed data truncated: need $packedEnd, have ${data.size.toLong()}"
            )
        }

        // lha.rs:72
        budget.reserveLh5Output(header.packedSize, header.originalSize)

        // lha.rs:73: decode_lh5_data(&data[data_start..packed_end], original_size)
        val dataSlice = data.copyOfRange(header.dataStart.toInt(), packedEnd.toInt())
        val bytes = decodeLh5Data(dataSlice, header.originalSize)

        return LhaMember( // lha.rs:74-79
            filename = header.filename,
            packedSize = header.packedSize,
            originalSize = header.originalSize,
            bytes = bytes,
        )
    }

    // ===== ヘッダ解析 =====

    /** lha.rs:82-87 の LhaHeader 相当（private）。 */
    private class LhaHeader(
        val filename: String,
        val packedSize: Long,
        val originalSize: Long,
        val dataStart: Long,
    )

    /**
     * LHA ヘッダを解析する。
     * lha.rs:89-123 の parse_lha_header の完全移植。
     *
     * ヘッダレイアウト（行番号は lha.rs）:
     * - byte 0: header_size（lha.rs:94）
     * - data_start = 2 + header_size（lha.rs:95-97）
     * - byte 2..7: "-lh5-" メソッド識別（lha.rs:101）
     * - byte 7..11: packed_size（u32 LE、lha.rs:105）
     * - byte 11..15: original_size（u32 LE、lha.rs:106）
     * - byte 21: filename_len（lha.rs:107）
     * - byte 22..22+filename_len: filename（UTF-8 lossy、lha.rs:115）
     */
    private fun parseLhaHeader(data: ByteArray): LhaHeader {
        // lha.rs:90-92
        if (data.size < 24) {
            throw InvalidDataException("LHA member header is too short")
        }

        val headerSize = data[0].toLong() and 0xFFL // lha.rs:94
        val dataStart = 2L + headerSize // lha.rs:95-97
        if (dataStart > data.size.toLong()) { // lha.rs:98-100
            throw InvalidDataException("LHA member header is truncated")
        }
        if (!data.copyOfRange(2, 7).contentEquals(LH5_METHOD)) { // lha.rs:101: &data[2..7] != b"-lh5-"
            throw UnsupportedFormatException("LHA method other than -lh5-")
        }

        val packedSize = readU32Le(data, 7) // lha.rs:105
        val originalSize = readU32Le(data, 11) // lha.rs:106
        val filenameLen = data[21].toInt() and 0xFF // lha.rs:107
        val filenameStart = 22 // lha.rs:108
        val filenameEnd = filenameStart + filenameLen // lha.rs:109-111
        if (filenameEnd > dataStart) { // lha.rs:112-114
            throw InvalidDataException("LHA filename exceeds header")
        }
        // lha.rs:115: String::from_utf8_lossy(&data[22..filename_end])
        // Kotlin の decodeToString() は UTF-8 の lossy 変換（不正シーケンスを U+FFFD に置換）
        val filename = data.copyOfRange(filenameStart, filenameEnd).decodeToString()

        return LhaHeader(filename, packedSize, originalSize, dataStart) // lha.rs:116-122
    }

    /**
     * lha.rs:125-130 の read_u32_le（オフセット指定で u32 Little-Endian を読む）。
     */
    private fun readU32Le(data: ByteArray, offset: Int): Long {
        if (offset + 4 > data.size) { // lha.rs:126-128: data.get(offset..offset+4)
            throw InvalidDataException("LHA header integer is truncated")
        }
        return (data[offset].toLong() and 0xFFL) or
            ((data[offset + 1].toLong() and 0xFFL) shl 8) or
            ((data[offset + 2].toLong() and 0xFFL) shl 16) or
            ((data[offset + 3].toLong() and 0xFFL) shl 24) // lha.rs:129: u32::from_le_bytes
    }

    // ===== LH5 本体 =====

    /**
     * LH5 圧縮データ本体を展開する。
     * lha.rs:132-181 の decode_lh5_data の完全移植。
     *
     * ループ構造（lha.rs:146-178）:
     * - block_remaining が尽きたらブロックヘッダを読む（lha.rs:147-152）
     *   - ブロック先頭 16-bit をそのまま block_remaining として読む
     *     （lha.rs:148。LHA5 の仕様上の 16-bit フラグ（0=符号表あり / 1=raw literal）
     *     に lha.rs の実装は分岐せず、16-bit 値をそのままブロックカウントとして使うため、
     *     本移植も lha.rs と同一に扱う）
     *   - pt (符号長) デコーダを 5-bit PT 長列 + special_index=3 で読む（lha.rs:149、read_pt_len NT=19）
     *   - code デコーダを 9-bit 長さコード数で読む（lha.rs:150、read_c_len NC=510）
     *   - position デコーダを 4-bit PT 長列で読む（lha.rs:151、read_pt_len NP=14、special なし）
     * - ブロック 1 コードごとに block_remaining を saturating_dec（lha.rs:154）
     * - code < 256 ならリテラル 1 バイト（lha.rs:156-164）
     * - code >= 256 なら base 253 の長さ + position コードの距離でバック参照（lha.rs:166-177）
     *
     * original_size で打ち切り（lha.rs:146 の while 条件 + copy の break lha.rs:208）。
     */
    private fun decodeLh5Data(data: ByteArray, originalSize: Long): ByteArray {
        val reader = BitReader(data) // lha.rs:133
        val output = ByteArrayOutputStream() // lha.rs:134
        // lha.rs:135-139: Vec::try_reserve_exact(original_size) は事前確保のみ。
        // Kotlin 側は ByteArrayOutputStream の内部成長に任せる（機能影響なし）。
        var dictionary = ByteArray(LH5_DICSIZ) // lha.rs:140: vec![0u8; LH5_DICSIZ]
        var dictionaryPos = 0L // lha.rs:141
        var blockRemaining = 0L // lha.rs:142
        var codeDecoder = HuffmanDecoder.single(0L) // lha.rs:143
        var positionDecoder = HuffmanDecoder.single(0L) // lha.rs:144

        // lha.rs:146
        while (output.size().toLong() < originalSize) {
            if (blockRemaining == 0L) { // lha.rs:147
                blockRemaining = reader.readBits(16) // lha.rs:148
                val ptDecoder = readPtLen(reader, LH5_NT, 5, 3) // lha.rs:149
                codeDecoder = readCLen(reader, ptDecoder) // lha.rs:150
                positionDecoder = readPtLen(reader, LH5_NP, 4, -1) // lha.rs:151（special=None → -1）
            }

            // lha.rs:154: block_remaining.saturating_sub(1)
            blockRemaining = blockRemaining.coerceAtLeast(0L) - 1L
            val code = codeDecoder.decode(reader) // lha.rs:155
            if (code < 256L) { // lha.rs:156
                // lha.rs:157-162: push_decoded_byte(output, dictionary, dictionary_pos, code)
                dictionaryPos = pushDecodedByte(output, dictionary, dictionaryPos, code)
                continue // lha.rs:163
            }

            // lha.rs:166-168: match_len = code.checked_sub(LH5_THRESHOLD_BASE)
            val matchLen = if (code < LH5_THRESHOLD_BASE) {
                throw InvalidDataException("invalid LH5 match length code")
            } else {
                code - LH5_THRESHOLD_BASE
            }
            // lha.rs:169: distance = decode_position + 1
            val distance = decodePosition(reader, positionDecoder) + 1
            // lha.rs:170-177: copy_from_dictionary(...)
            dictionaryPos = copyFromDictionary(output, dictionary, dictionaryPos, distance, matchLen, originalSize)
        }

        return output.toByteArray() // lha.rs:180
    }

    /**
     * lha.rs:183-192 の push_decoded_byte を移植。
     *
     * output に 1 バイト追記し、dictionary リングバッファに書き込む
     * （lha.rs:189-190）。呼び出し側へラップ済みの新しい dictionary_pos を返す
     * （lha.rs:191: `(*dictionary_pos + 1) & (LH5_DICSIZ - 1)`、Rust の &mut による
     * 書き込み返りを Kotlin では戻り値で表現）。
     */
    private fun pushDecodedByte(
        output: ByteArrayOutputStream,
        dictionary: ByteArray,
        dictionaryPos: Long,
        byte: Long,
    ): Long {
        output.write((byte and 0xFFL).toInt()) // lha.rs:189
        dictionary[dictionaryPos.toInt()] = (byte and 0xFFL).toByte() // lha.rs:190
        return (dictionaryPos + 1) and (LH5_DICSIZ - 1).toLong() // lha.rs:191
    }

    /**
     * lha.rs:194-216 の copy_from_dictionary を移植。
     *
     * dictionary_pos から distance だけ先行した位置を起点に match_len 分を
     * 1 バイトずつリングバッファ経由で output へ複製する（lha.rs:206-214）。
     * original_size 到達で打ち切り（lha.rs:208-210）。
     * 新しい dictionary_pos を返す（Rust の &mut dictionary_pos 相当）。
     */
    private fun copyFromDictionary(
        output: ByteArrayOutputStream,
        dictionary: ByteArray,
        dictionaryPos: Long,
        distance: Long,
        matchLen: Long,
        originalSize: Long,
    ): Long {
        // lha.rs:202-204
        if (distance == 0L || distance > LH5_DICSIZ.toLong()) {
            throw InvalidDataException("invalid LH5 match distance")
        }

        // lha.rs:206: read_pos = (dictionary_pos + LH5_DICSIZ - distance) & (LH5_DICSIZ - 1)
        var readPos = (dictionaryPos + LH5_DICSIZ.toLong() - distance) and (LH5_DICSIZ - 1).toLong()
        var pos = dictionaryPos
        for (i in 0 until matchLen.toInt()) { // lha.rs:207
            if (output.size().toLong() >= originalSize) { // lha.rs:208-210
                break
            }
            val byte = dictionary[readPos.toInt()].toLong() and 0xFFL // lha.rs:211
            readPos = (readPos + 1) and (LH5_DICSIZ - 1).toLong() // lha.rs:212
            pos = pushDecodedByte(output, dictionary, pos, byte) // lha.rs:213
        }
        return pos // lha.rs:215
    }

    /**
     * lha.rs:218-225 の decode_position を移植。
     *
     * position コード 0 は距離 0。それ以外は
     * `1 << (code-1) + 追加 (code-1) bits`（lha.rs:223）。
     */
    private fun decodePosition(reader: BitReader, decoder: HuffmanDecoder): Long {
        val code = decoder.decode(reader)
        return if (code == 0L) {
            0L // lha.rs:221-222
        } else {
            (1L shl (code - 1).toInt()) + reader.readBits((code - 1).toInt()) // lha.rs:223
        }
    }

    /**
     * lha.rs:227-265 の read_pt_len を移植（PT = パッカートリー長）。
     *
     * encoded_count == 0 ならその場に単一 symbol を読む（lha.rs:234-236）。
     * それ以外は length 列を読む。length==7 なら MSB-continued 形式（lha.rs:248-252）。
     * [specialIndex] の位置で 2-bit の zero run が挿入される（lha.rs:256-261、
     * Option::None 相当は -1 で表す）。
     */
    private fun readPtLen(
        reader: BitReader,
        symbolCount: Int,
        bitCount: Int,
        specialIndex: Long,
    ): HuffmanDecoder {
        val encodedCount = reader.readBits(bitCount).toInt() // lha.rs:233
        if (encodedCount == 0) { // lha.rs:234-235
            return HuffmanDecoder.single(reader.readBits(bitCount)) // lha.rs:235
        }

        val lengths = LongArray(symbolCount) // lha.rs:238
        var index = 0L // lha.rs:239
        while (index < encodedCount) { // lha.rs:240
            if (index >= symbolCount) { // lha.rs:241-245
                throw InvalidDataException("LH5 PT length count exceeds table")
            }

            var length = reader.readBits(3).toInt() // lha.rs:247
            if (length == 7) { // lha.rs:248-252
                while (reader.readBit().toInt() != 0) { // lha.rs:249
                    length += 1 // lha.rs:250
                }
            }
            lengths[index.toInt()] = length.toLong() // lha.rs:253
            index += 1 // lha.rs:254

            if (specialIndex != -1L && index == specialIndex) { // lha.rs:256
                index += reader.readBits(2).toInt().toLong() // lha.rs:257
                if (index > symbolCount) { // lha.rs:258-260
                    throw InvalidDataException("LH5 PT zero run exceeds table")
                }
            }
        }

        return HuffmanDecoder.fromLengths(lengths) // lha.rs:264
    }

    /**
     * lha.rs:267-301 の read_c_len を移植（NC=510 個の符号長の列）。
     *
     * encoded_count == 0 なら単一 symbol（lha.rs:269-271）。
     * それ以外は pt_decoder の code を 1 ずつ読む:
     * - code 0 → zero +1、code 1 → 4-bit+3、code 2 → 9-bit+20 の zero run（lha.rs:283-293）
     * - code >= 3 → lengths[index] = code - 2、index += 1（lha.rs:294-297）
     */
    private fun readCLen(reader: BitReader, ptDecoder: HuffmanDecoder): HuffmanDecoder {
        val encodedCount = reader.readBits(9).toInt() // lha.rs:268
        if (encodedCount == 0) { // lha.rs:269-270
            return HuffmanDecoder.single(reader.readBits(9)) // lha.rs:270
        }

        val lengths = LongArray(LH5_NC) // lha.rs:273
        var index = 0L // lha.rs:274
        while (index < encodedCount) { // lha.rs:275
            if (index >= LH5_NC) { // lha.rs:276-280
                throw InvalidDataException("LH5 C length count exceeds table")
            }
            val code = ptDecoder.decode(reader).toInt() // lha.rs:282
            if (code <= 2) { // lha.rs:283
                val zeroCount = when (code) {
                    0 -> 1L // lha.rs:285
                    1 -> reader.readBits(4).toInt().toLong() + 3 // lha.rs:286
                    2 -> reader.readBits(9).toInt().toLong() + 20 // lha.rs:287
                    else -> error("unreachable") // lha.rs:288: _ => unreachable!()
                }
                index += zeroCount // lha.rs:290
                if (index > LH5_NC) { // lha.rs:291-293
                    throw InvalidDataException("LH5 C zero run exceeds table")
                }
            } else { // lha.rs:294-297
                lengths[index.toInt()] = (code - 2).toLong()
                index += 1
            }
        }

        return HuffmanDecoder.fromLengths(lengths) // lha.rs:300
    }

    // ===== ビットリーダー =====

    /**
     * lha.rs:303-339 の BitReader を移植。MSB-first。
     *
     * [readBit] は `[byte >> (7 - bit_pos)] & 1` で抽出（lha.rs:331）。
     * bit_pos が 8 になったら次の byte へ（lha.rs:332-336）。
     * バイトが尽きる前に読むと "LH5 bitstream ended early"（lha.rs:328-330）。
     */
    private class BitReader(private val data: ByteArray) {
        private var bytePos = 0 // lha.rs:311
        private var bitPos = 0 // lha.rs:312

        /**
         * lha.rs:318-324 の read_bits（MSB-first の count bits を 1 値に詰める）。
         */
        fun readBits(count: Int): Long {
            var value = 0L
            for (i in 0 until count) {
                value = (value shl 1) or readBit() // lha.rs:321
            }
            return value
        }

        /**
         * lha.rs:326-338 の read_bit。
         */
        fun readBit(): Long {
            if (bytePos >= data.size) { // lha.rs:328-330
                throw InvalidDataException("LH5 bitstream ended early")
            }
            val byte = data[bytePos].toInt()
            val bit = (byte shr (7 - bitPos)) and 1 // lha.rs:331
            bitPos += 1 // lha.rs:332
            if (bitPos == 8) { // lha.rs:333-336
                bitPos = 0
                bytePos += 1
            }
            return bit.toLong()
        }
    }

    // ===== ハフマン =====

    /**
     * lha.rs:341-409 の HuffmanDecoder を移植。
     *
     * Rust の `enum HuffmanDecoder { Single(usize), Tree(Vec<HuffmanNode>) }` を
     * Kotlin の sealed class で表現（lha.rs:341-344）。
     */
    private sealed class HuffmanDecoder {

        /**
         * lha.rs:342 の Single(usize)。
         * decode は常にその symbol を返す（lha.rs:394-395）。
         */
        class Single(private val symbol: Long) : HuffmanDecoder() {
            override fun decode(reader: BitReader): Long = symbol
        }

        /**
         * lha.rs:343 の Tree(Vec<HuffmanNode>)。
         * decode はループで bit を読む（lha.rs:396-407）。
         */
        class Tree(private val nodes: MutableList<HuffmanNode>) : HuffmanDecoder() {
            override fun decode(reader: BitReader): Long {
                var index = 0L // lha.rs:397
                while (true) {
                    val node = nodes[index.toInt()]
                    if (node.symbol != -1L) { // lha.rs:399-400
                        return node.symbol
                    }
                    val bit = reader.readBit().toInt() // lha.rs:402
                    val next = node.children[bit] // lha.rs:403
                    if (next == -1L) { // lha.rs:404
                        throw InvalidDataException("invalid LH5 Huffman code")
                    }
                    index = next
                }
            }
        }

        abstract fun decode(reader: BitReader): Long

        companion object {
            /** lha.rs:347-349 の single。 */
            fun single(symbol: Long): HuffmanDecoder = Single(symbol)

            /**
             * lha.rs:351-391 の from_lengths（canonical ハフマン）。
             *
             * 使用 symbol 0 件なら失敗（lha.rs:357-359）、1 件なら Single 化（lha.rs:360-362）。
             * max_bits == usize::MAX なら失敗（lha.rs:365-369、64-bit で 64 以上）。
             * canonical code を counts + next_code と計算（lha.rs:371-381）し、
             * insert_code でツリーを構築（lha.rs:383-388）。
             */
            fun fromLengths(lengths: LongArray): HuffmanDecoder {
                val used = mutableListOf<Pair<Long, Long>>() // (symbol, length)
                for (symbol in lengths.indices) { // lha.rs:352-356
                    val length = lengths[symbol]
                    if (length > 0L) {
                        used.add(symbol.toLong() to length)
                    }
                }
                if (used.isEmpty()) { // lha.rs:357-359
                    throw InvalidDataException("empty LH5 Huffman tree")
                }
                if (used.size == 1) { // lha.rs:360-362
                    return Single(used[0].first)
                }

                val maxBits = used.maxOf { it.second } // lha.rs:364
                if (maxBits >= 64L) { // lha.rs:365-369: usize::BITS = 64
                    throw InvalidDataException("LH5 Huffman code length is too large")
                }

                val counts = LongArray(maxBits.toInt() + 1) // lha.rs:371
                for ((_, length) in used) { // lha.rs:372-374
                    counts[length.toInt()] += 1
                }

                val nextCode = LongArray(maxBits.toInt() + 1) // lha.rs:376
                var code = 0L // lha.rs:377
                for (bits in 1..maxBits.toInt()) { // lha.rs:378-381
                    code = (code + counts[bits - 1]) shl 1
                    nextCode[bits] = code
                }

                val nodes = mutableListOf(HuffmanNode()) // lha.rs:383
                for ((symbol, length) in used) { // lha.rs:384-388
                    val c = nextCode[length.toInt()]
                    nextCode[length.toInt()] += 1
                    insertCode(nodes, c, length, symbol)
                }

                return Tree(nodes)
            }
        }
    }

    /**
     * lha.rs:411-415 の HuffmanNode を移植。
     *
     * Rust の `Option<usize>` を -1 で表現（None）。
     */
    private class HuffmanNode(
        var symbol: Long = -1L,
        val children: LongArray = longArrayOf(-1L, -1L),
    )

    /**
     * lha.rs:417-442 の insert_code を移植。
     *
     * canonical code 上位 bit からツリーを降下しながらノードを生成（lha.rs:423-435）。
     * 途中に葉がある場合は "ambiguous"（lha.rs:425-428）、
     * 終端に既設の葉または子がある場合は "duplicate"（lha.rs:437-440）をエラー。
     */
    private fun insertCode(nodes: MutableList<HuffmanNode>, code: Long, length: Long, symbol: Long) {
        var index = 0L // lha.rs:423
        for (shift in (0 until length.toInt()).reversed()) { // lha.rs:424
            val node = nodes[index.toInt()]
            if (node.symbol != -1L) { // lha.rs:425-427
                throw InvalidDataException("ambiguous LH5 Huffman tree")
            }

            val bit = ((code shr shift) and 1L).toInt() // lha.rs:429
            if (node.children[bit] == -1L) { // lha.rs:430
                node.children[bit] = nodes.size.toLong()
                nodes.add(HuffmanNode())
            }
            index = node.children[bit] // lha.rs:434
        }

        val leaf = nodes[index.toInt()]
        if (leaf.symbol != -1L || leaf.children.any { it != -1L }) { // lha.rs:437-439
            throw InvalidDataException("duplicate LH5 Huffman code")
        }
        leaf.symbol = symbol // lha.rs:440
    }
}
