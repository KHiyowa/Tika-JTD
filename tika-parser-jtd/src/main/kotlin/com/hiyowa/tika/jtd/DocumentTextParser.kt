package com.hiyowa.tika.jtd

import java.io.ByteArrayOutputStream
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import kotlin.math.max
import kotlin.math.min

/**
 * DocumentText ストリームパーサー。移植元: OpenJTD `rjtd-core/src/document_text.rs`。
 *
 * 全バイト列を UTF-16BE 語列として扱い、RFC 0009 のマーカー（0x001c/0x001d/0x001e/0x001f）と
 * 制御境界で [ParsedDocumentText] を作り上げる。
 *
 * 移植スコープ（本ステップ）:
 * - マジック `SsmgV.01` + `TextV.01` + w[9]==0x0001 / マーカーレス raw 型ゲート
 *   → [parseRawTextSegment]（先頭 raw 経路、0x001f 不要）
 * - 混在型（P1）プロローグの raw 先行エミット（[decodeRawPrologue]）
 * - マーカー走査（0x001f run 開始判定・0x001d インライン開始・0x001e 終端・制御境界）
 * - [readDocumentTextPayload]（3 経路フォールバック: /DocumentText → /JSCompDocument
 *   展開 → 埋め込みスキャン）と [embeddedDocumentText]（SsmgV.01 全出現位置からの
 *   fragment 回収 + 尤度フィル）
 *
 * 未移植（Rust 版との意図的な差分）:
 * - TODO(Step5): ルビ promotion・自動番号付け（NumberingState / paragraph_header_prefix）・
 *   trim_trailing_exposed_controls（末尾露出制御文字トリム）
 * - [hasEmbeddedDocumentText] は Rust の完全解析成功ベース（`has_embedded_document_text`）ではなく、
 *   検出パスの効率化のためマジック候補存在チェックの軽量版（完全解析は
 *   [readDocumentTextPayload] が担当）
 * - map_document_text（位置ミラー）は Step 2b で分離移植
 */
object DocumentTextParser {

    // 移植元 Rust の private 定数を同一値で維持
    private val DOCUMENT_TEXT_MAGIC: ByteArray = "SsmgV.01".toByteArray(Charsets.ISO_8859_1)
    private val TEXT_SEGMENT_NAME: ByteArray = "TextV.01".toByteArray(Charsets.ISO_8859_1)
    // SsmgV.01 のセグメント数フィールド: w[9]=0x0001 は raw-text 単一セグメント、0x0002 が通常
    private const val SSMG_RAW_TEXT_SEGMENT_COUNT = 0x0001
    // SsmgV.01 (4語) + 先頭ヘッダ (4語) + セグメント数 (2語)
    private const val SSMG_HEADER_WORDS = 10
    // TextV.01 本文コンテンツヘッダ語数（32 バイト）
    private const val TEXT_CONTENT_HEADER_WORDS = 16
    // 本文語数フィールドのバイトオフセット（TextV.01 長の先頭語）
    private const val CONTENT_UNIT_COUNT_OFFSET = 28
    // ルビ等スキップインライン区間の最大語数
    private const val SKIPPED_INLINE_MAX_UNITS = 256

    // RFC 0009 インラインセレクタ文脈の固定語列
    private const val CONTEXT_OPENING = 0x001c
    private const val CONTEXT_FLAG_1 = 0x0001
    private const val CONTEXT_FLAG_2 = 0x0007
    private const val CONTEXT_ZERO = 0x0000
    private const val CONTEXT_SKIPPED_FLAG = 0x0001

    /**
     * DocumentText ストリームを解析する。Rust `parse_document_text` と同一挙動。
     */
    fun parseDocumentText(data: ByteArray): ParsedDocumentText {
        // SsmgV.01 w[9]=0x0001: 単一 raw-text セグメント（0x001f マーカーなし）。
        // レイアウト: SsmgV.01 ヘッダ (10語) + TextV.01 名 (4語) + 長 (2語) + 本文。
        // 実原本には w[9]>=0x0003 のマーカーレス raw 型も存在し、同一レイアウトで通す。
        // マーカー型（通常）ファイルは本文に 0x001c/0x001d/0x001f を含み
        // [isMarkerlessRawTextSpan] に落ちないため、通常パスの挙動は不変（リグレッションガード）。
        if (data.startsWith(DOCUMENT_TEXT_MAGIC)) {
            val units = toUnits(data)
            if (hasBytesAt(data, SSMG_HEADER_WORDS * 2, TEXT_SEGMENT_NAME) &&
                (units.getOrNull(9) == SSMG_RAW_TEXT_SEGMENT_COUNT || isMarkerlessRawTextSpan(units))
            ) {
                return parseRawTextSegment(units)
            }
        }

        val units = toUnits(data)
        val unitLimit = min(documentTextUnitLimit(data) ?: units.size, units.size)
        val elements = mutableListOf<DocumentTextElement>()
        val run = StringBuilder()
        var readingText = false
        var hasProse = false

        // 混在型（P1）: 最初のマーカーより手前の raw 前置きを、後続マーカー本文より前で
        // TextRun として1つ emit する。マーカー型ファイル（前置き空・printable なし）は
        // 発火せず、通常のマーカー走査のみで現状と同一の出力を作る（リグレッションガード）。
        decodeRawPrologue(data, units)?.let { decoded ->
            elements.add(DocumentTextElement.TextRun(decoded.text))
            hasProse = true
        }

        var index = 0
        while (index < unitLimit) {
            val code = units[index]
            if (code == DocumentTextConstants.TEXT_RUN_MARKER) {
                if (!isDocumentTextRunStart(units, index, hasProse)) {
                    index++
                    continue
                }
                pushRun(elements, run)
                // TODO(Step5): 自動番号付け paragraph_header_prefix（NumberingState 連動）
                // は本ステップの対象外。移植すると段落先頭に「第1章」等の接頭辞が追加されるため、
                // 移植時はテストの期待値を伴う形で追加すること。
                readingText = true
                hasProse = true
                index++
                continue
            }

            if (code == DocumentTextConstants.INLINE_TEXT_START) {
                pushRun(elements, run)
                readingText = false
                val selector = inlineTextSelector(units, index)
                when {
                    selector != null -> {
                        index = pushInlineSegment(elements, units, index, selector)
                        hasProse = true
                    }
                    skippedInlineSelector(units, index) != null -> {
                        val skipped = readSkippedInlineSegment(units, index)
                        if (skipped != null) {
                            elements.add(skipped.first)
                            index = skipped.second
                        } else {
                            // Rust の let-チェーン失敗時（区間上限超過）のフォールスルー
                            elements.add(DocumentTextElement.ControlBoundary(code))
                            index++
                        }
                    }
                    else -> {
                        elements.add(DocumentTextElement.ControlBoundary(code))
                        index++
                    }
                }
                continue
            }

            if (readingText) {
                if (isControlBoundary(code) || isInvalidScalar(code)) {
                    pushRun(elements, run)
                    elements.add(DocumentTextElement.ControlBoundary(code))
                    readingText = code == DocumentTextConstants.TEXT_ROW_DELIMITER ||
                        code == DocumentTextConstants.DOCUMENT_TEXT_PAGE_BREAK_CONTROL ||
                        code == DocumentTextConstants.DOCUMENT_TEXT_INLINE_SPACE_CONTROL
                } else {
                    run.append(code.toChar())
                    hasProse = true
                }
            }

            index++
        }

        pushRun(elements, run)
        return ParsedDocumentText.of(elements)
    }

    /**
     * DocumentText ストリームから平文を抽出する。Rust `extract_document_text` と同一。
     */
    fun extractDocumentText(data: ByteArray): String = parseDocumentText(data).plainText()

    // ------------------------------------------------------------------
    // Payload 読み出し（3 経路フォールバック / 埋め込みスキャン）
    // 移植元: Rust `read_document_text_payload_with_budget` /
    // `read_compressed_or_embedded_document_text` / `embedded_document_text` /
    // `clean_embedded_text` / `is_plausible_embedded_line` / `is_plausible_embedded_character` /
    // `find_document_text_magic_offsets`（document_text.rs）
    // ------------------------------------------------------------------

    // 埋め込み DocumentText の 1 候補あたりの走査スパン上限。document_text.rs:11 と同一値。
    private const val EMBEDDED_DOCUMENT_TEXT_MAX_SPAN = 64 * 1024

    // 埋め込み尤度判定の対象外になる CJK 補助記号群。document_text.rs:442-469 の
    // matches! 文字列集合と同一（一部は上述のコードポイント範囲と重複するが、
    // 移植元をそのまま反映した形にするため維持）。
    private val EMBEDDED_PLAUSIBLE_EXTRA_CHARACTERS: Set<Char> = setOf(
        '、', '。', '・', '「', '」', '『', '』', '【', '】', '（', '）', '［', '］',
        '→', '←', '↑', '↓', '～', '…', '◎', '○', '●', '◆', '☆', '★', '※',
    )

    /**
     * DocumentText をデフォルト上限で読み出す。Rust `read_document_text_payload` 相当。
     *
     * 見つからない場合は [NotFoundException] をスローする
     * （Rust の `Error::NotFound("stream \`/DocumentText\`")` 相当）。
     */
    fun readDocumentTextPayload(data: ByteArray): DocumentTextPayload {
        val budget = ParseLimits.DEFAULT.decompressionBudget()
        return readDocumentTextPayloadWithBudget(data, budget)
    }

    /**
     * 共有 [DecompressionBudget] で DocumentText を読み出す。
     * Rust `read_document_text_payload_with_budget` と同一の 3 経路フォールバック:
     * 1. `/DocumentText` が直接読める
     * 2. `/JSCompDocument` が JustCompressedDocument → 展開 → 内部 CFB を再度開封して `/DocumentText`
     * 3. バイト列全体の埋め込みスキャン（[embeddedDocumentText]）
     *
     * 1 と 2 の CFS 読み出しは同一の [POIFSFileSystem] を再利用し、
     * 同一コンテナを二度開かない（内部 CFB は別コンテナなので改めて開く）。
     */
    internal fun readDocumentTextPayloadWithBudget(data: ByteArray, budget: DecompressionBudget): DocumentTextPayload {
        budget.checkInputSize(data.size.toLong())
        // 外部 CFB を一回だけ開く（経路 1・2 の読み出しを共有）
        val fs = JtdContainerReader.open(data)
        try {
            val stream = JtdContainerReader.readStream(fs, DocumentTextConstants.DOCUMENT_TEXT_PATH)
            return if (stream != null) {
                DocumentTextPayload(
                    DocumentTextConstants.DOCUMENT_TEXT_PATH,
                    stream,
                    parseDocumentText(stream).plainText(),
                )
            } else {
                readCompressedOrEmbeddedDocumentText(data, fs, budget)
            }
        } finally {
            fs.close()
        }
    }

    /**
     * 経路 2・3。`/JSCompDocument` がなければ（または JustCompressedDocument でなければ）
     * 埋め込みスキャンへフォールバックする。Rust `read_compressed_or_embedded_document_text` 相当。
     */
    private fun readCompressedOrEmbeddedDocumentText(
        data: ByteArray,
        fs: POIFSFileSystem,
        budget: DecompressionBudget,
    ): DocumentTextPayload {
        val jsCompDocument = JtdContainerReader.readStream(fs, DocumentTextConstants.COMPRESSED_DOCUMENT_PATH)
            ?: return readEmbeddedDocumentText(data)
        if (!JustCompressedDocument.isJustCompressedDocument(jsCompDocument)) {
            return readEmbeddedDocumentText(data)
        }

        val innerDocument = JustCompressedDocument.decompressWithBudget(jsCompDocument, budget)
        // 内部 CFB は外部コンテナとは別物なので、ここで初めて開く（same-container 二度開きではない）。
        val innerBytes = JtdContainerReader.withFileSystem(innerDocument) { innerFs ->
            JtdContainerReader.readStream(innerFs, DocumentTextConstants.DOCUMENT_TEXT_PATH)
                ?: throw NotFoundException("stream `${DocumentTextConstants.DOCUMENT_TEXT_PATH}`")
        }
        return DocumentTextPayload(
            DocumentTextConstants.DOCUMENT_TEXT_PATH,
            innerBytes,
            parseDocumentText(innerBytes).plainText(),
        )
    }

    /**
     * 埋め込みスキャン結果から Payload を作る。Rust `read_embedded_document_text` 相当。
     * 有効な fragment が一つもなければ [NotFoundException]（Rust の NotFound 伝播と同一）。
     */
    private fun readEmbeddedDocumentText(data: ByteArray): DocumentTextPayload =
        embeddedDocumentText(data)
            ?: throw NotFoundException("stream `${DocumentTextConstants.DOCUMENT_TEXT_PATH}`")

    /**
     * 埋め込み DocumentText の候補の有無を確認する（ヒット有無のみ）。
     *
     * Rust `has_embedded_document_text`（`embedded_document_text(data).is_some()`、
     * 完全解析成功ベース）の軽量版。Kotlin 版では [JtdFormatDetector.detect] の 4 段目が
     * この結果のみを使うため、ここでは SsmgV.01 マジック候補の存在確認に留める。
     * 完全な解析（尤度フィル・span 上限付き）は [embeddedDocumentText] が
     * [readDocumentTextPayload] 経由で行う（detect での二度目の全文字列解析を避ける）。
     */
    fun hasEmbeddedDocumentText(data: ByteArray): Boolean =
        findDocumentTextMagicOffsets(data).isNotEmpty()

    /**
     * バイト列に出現する SsmgV.01 マジックの全位置から埋め込み DocumentText の
     * fragment を回収し [DocumentTextPayload] として結合する。
     * Rust `embedded_document_text` の完全移植。
     *
     * 各出現位置に対し `end = min(次出現位置, 出現位置 + [EMBEDDED_DOCUMENT_TEXT_MAX_SPAN])` の
     * スパンを解析（[extractDocumentText]）し、[cleanEmbeddedText] で整える。
     * 空テキスト・重複テキストの span はスキップする。採用された複数 span は bytes を
     * `[0, 0]` 区切りで連結、text を `"\n"` で連結する。
     * 採用 fragment がなければ null。
     */
    internal fun embeddedDocumentText(data: ByteArray): DocumentTextPayload? {
        val offsets = findDocumentTextMagicOffsets(data)
        val bytes = ByteArrayOutputStream()
        val textParts = mutableListOf<String>()

        for (index in offsets.indices) {
            val start = offsets[index]
            val nextStart = offsets.getOrNull(index + 1) ?: data.size
            val end = min(nextStart, start + EMBEDDED_DOCUMENT_TEXT_MAX_SPAN)
            if (end <= start) continue
            val fragment = data.copyOfRange(start, end)
            val text = cleanEmbeddedText(extractDocumentText(fragment))
            if (text.isBlank() || textParts.contains(text)) continue

            if (bytes.size() > 0) bytes.write(byteArrayOf(0, 0))
            bytes.write(fragment)
            textParts.add(text)
        }

        if (textParts.isEmpty()) return null
        return DocumentTextPayload(
            DocumentTextConstants.EMBEDDED_DOCUMENT_TEXT_PATH,
            bytes.toByteArray(),
            textParts.joinToString("\n"),
        )
    }

    /**
     * 埋め込み fragment のテキストを整形する。Rust `clean_embedded_text` と同一:
     * `\r\n` で分割し、各行の両端から `'\0'` をトリム、空行・[isPlausibleEmbeddedLine]
     * で弾かれる非尤度行を除去、残りを `"\n"` で再接続する。
     */
    private fun cleanEmbeddedText(text: String): String {
        val keptLines = mutableListOf<String>()
        for (rawLine in text.split('\r', '\n')) {
            val line = rawLine.trimStart('\u0000').trimEnd('\u0000')
            if (line.isBlank() || !isPlausibleEmbeddedLine(line)) continue
            keptLines.add(line)
        }
        return keptLines.joinToString("\n")
    }

    /**
     * 埋め込み fragment の 1 行の尤度判定。Rust `is_plausible_embedded_line` と同一:
     * 空白を除外した総語数が 0 を超え、かつ尤度語数が全体の 80% 以上
     * （`plausible * 100 >= total * 80`）。
     */
    private fun isPlausibleEmbeddedLine(line: String): Boolean {
        var total = 0
        var plausible = 0
        for (character in line) {
            if (character.isWhitespace()) continue
            total++
            if (isPlausibleEmbeddedCharacter(character)) plausible++
        }
        return total > 0 && plausible * 100 >= total * 80
    }

    /**
     * 埋め込み fragment で妥当な文字か判定する。
     * Rust `is_plausible_embedded_character`（document_text.rs:432-470）の完全移植:
     * ASCII graphic、0x3000..=0x30FF / 0x31F0..=0x31FF / 0x3200..=0x33FF /
     * 0x4E00..=0x9FFF / 0xFF00..=0xFFEF、および [EMBEDDED_PLAUSIBLE_EXTRA_CHARACTERS]。
     */
    private fun isPlausibleEmbeddedCharacter(character: Char): Boolean {
        val code = character.code
        if (code in 0x21..0x7E) return true // ASCII graphic（Rust char::is_ascii_graphic）
        return code in 0x3000..0x30FF ||
            code in 0x31F0..0x31FF ||
            code in 0x3200..0x33FF ||
            code in 0x4E00..0x9FFF ||
            code in 0xFF00..0xFFEF ||
            character in EMBEDDED_PLAUSIBLE_EXTRA_CHARACTERS
    }

    /**
     * バイト列中の SsmgV.01 マジック出現位置をすべて集める。
     * Rust `find_document_text_magic_offsets` 相当。
     */
    private fun findDocumentTextMagicOffsets(data: ByteArray): List<Int> {
        val offsets = mutableListOf<Int>()
        val lastStart = data.size - DOCUMENT_TEXT_MAGIC.size
        if (lastStart < 0) return offsets
        @Suppress("LoopWithTooManyJumpStatements")
        outer@ for (offset in 0..lastStart) {
            for (i in DOCUMENT_TEXT_MAGIC.indices) {
                if (data[offset + i] != DOCUMENT_TEXT_MAGIC[i]) continue@outer
            }
            offsets.add(offset)
        }
        return offsets
    }

    /**
     * [decodePrologueUnits] の返り値（Rust `decode_prologue_units` の tuple 返しを
     * 名前付きフィールドに整形）。モジュール内（[HeaderTextReader] 等）で共用する。
     */
    data class DecodedPrologue(val text: String, val boundary: Int)

    /**
     * units[start..end) 内の最初の RFC 0009 マーカー（0x001c/0x001d/0x001f）の位置。
     * span 全体がマーカーレスの場合は null（先頭 raw 経路の管轄）。
     * 移植元 Rust の `pub(crate) fn first_text_marker` 相当。
     */
    internal fun firstTextMarker(units: IntArray, start: Int, end: Int): Int? {
        for (i in start until end) {
            if (units[i] == DocumentTextConstants.RECORD_START_MARKER ||
                units[i] == DocumentTextConstants.INLINE_TEXT_START ||
                units[i] == DocumentTextConstants.TEXT_RUN_MARKER
            ) {
                return i
            }
        }
        return null
    }

    /** [firstTextMarker] の [List] ベースオーバーロード（Header テキスト等の List 語列利用者向け）。 */
    internal fun firstTextMarker(units: List<Int>, start: Int, end: Int): Int? =
        firstTextMarker(units.toIntArray(), start, end)

    /**
     * units[start..end) を UTF-16BE 生の text として通読する。
     * CR(0x000d)/LF(0x000a) は行区切りとして保持、0x0000 は連続パディングとしてスキップ
     * （打ち切りではない）、それ以外の制御境界・無効スカラーで読みを打ち切る。
     * 少なくとも1語読めば (テキスト, 打ち切り unit 位置) を、空なら null を返す。
     * 移植元 Rust の `pub(crate) fn decode_prologue_units` 相当。
     */
    internal fun decodePrologueUnits(units: IntArray, start: Int, end: Int): DecodedPrologue? {
        val text = StringBuilder()
        var index = start
        while (index < end) {
            val code = units[index]
            when (code) {
                0x0000 -> {
                    index++
                    continue
                }
                0x000d -> {
                    text.append('\r')
                    index++
                    continue
                }
                0x000a -> {
                    text.append('\n')
                    index++
                    continue
                }
                else -> Unit
            }
            if (isInvalidScalar(code) || isControlBoundary(code)) break
            text.append(code.toChar())
            index++
        }
        return if (text.isEmpty()) null else DecodedPrologue(text.toString(), index)
    }

    /** [decodePrologueUnits] の [List] ベースオーバーロード（Header テキスト等の List 語列利用者向け）。 */
    internal fun decodePrologueUnits(units: List<Int>, start: Int, end: Int): DecodedPrologue? =
        decodePrologueUnits(units.toIntArray(), start, end)

    // ------------------------------------------------------------------
    // raw text 経路（SsmgV.01 / TextV.01）
    // ------------------------------------------------------------------

    // マジック + TextV.01 セグメント名の両方が揃っているときのみ true（混在型の識別子）。
    private fun isTextv01Segment(data: ByteArray): Boolean =
        data.startsWith(DOCUMENT_TEXT_MAGIC) &&
            hasBytesAt(data, SSMG_HEADER_WORDS * 2, TEXT_SEGMENT_NAME)

    // マーカーレス raw テキスト型（w[9] が 0x0001 より大きい実原本）の識別。
    // word 15 が本文長（語数）であり、本文領域 units[16..16+len] に RFC 0009 マーカーが
    // 一切含まれないときのみ raw デコードする。
    private fun isMarkerlessRawTextSpan(units: IntArray): Boolean {
        if (units.size < 16 || units[14] != 0x0000) return false
        val length = units[15]
        if (length == 0 || length > units.size - 16) return false
        return (16 until 16 + length).all { i ->
            units[i] != DocumentTextConstants.RECORD_START_MARKER &&
                units[i] != DocumentTextConstants.INLINE_TEXT_START &&
                units[i] != DocumentTextConstants.TEXT_RUN_MARKER
        }
    }

    // SsmgV.01 raw-text セグメントを解析: w[15] が語数、units[16..16+len] を UTF-16BE 本文とする。
    // 0x0000 で打ち切り、無効スカラーはスキップ。
    private fun parseRawTextSegment(units: IntArray): ParsedDocumentText {
        val headerWords = SSMG_HEADER_WORDS + 4 + 2 // 16
        val length = units.getOrNull(SSMG_HEADER_WORDS + 4 + 1) ?: return ParsedDocumentText.fromText("")
        val textStart = headerWords
        val textEnd = min(max(0, textStart + length), units.size)
        val run = StringBuilder()
        for (i in textStart until textEnd) {
            val code = units[i]
            if (code == 0x0000) break
            if (!isInvalidScalar(code)) {
                run.append(code.toChar())
            }
        }
        return if (run.isEmpty()) {
            ParsedDocumentText.fromText("")
        } else {
            ParsedDocumentText.of(listOf(DocumentTextElement.TextRun(run.toString())))
        }
    }

    // 本文語数上限を算出（document_text_unit_limit 相当）。
    // SsmgV.01 + TextV.01 ヘッダが揃う場合のみ [CONTENT_UNIT_COUNT_OFFSET] の u32 BE 語数 +
    // [TEXT_CONTENT_HEADER_WORDS] を返し、それ以外は null（units.len() で処理）。
    private fun documentTextUnitLimit(data: ByteArray): Int? {
        if (!data.startsWith(DOCUMENT_TEXT_MAGIC)) return null
        if (data.size < TEXT_CONTENT_HEADER_WORDS * 2) return null
        if (!hasBytesAt(data, SSMG_HEADER_WORDS * 2, TEXT_SEGMENT_NAME)) return null
        val offset = CONTENT_UNIT_COUNT_OFFSET
        val count = ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
        return TEXT_CONTENT_HEADER_WORDS + count
    }

    // P1 仕様: TextV.01 span の最初のマーカーより手前の UTF-16BE 生の
    // 前置き本文を raw デコードする。発火条件をすべて満たすとき (前置き文字列, 終端 unit)
    // を返し、それ以外は null（マーカー型ファイルは出力が変わらない）。
    private fun decodeRawPrologue(data: ByteArray, units: IntArray): DecodedPrologue? {
        if (!isTextv01Segment(data) || units.size < 16 || units[14] != 0x0000) return null
        val spanLength = units[15]
        if (spanLength == 0 || spanLength > units.size - 16) return null
        val start = TEXT_CONTENT_HEADER_WORDS
        val marker = firstTextMarker(units, start, start + spanLength) ?: return null
        if (marker == start) return null
        // 前置き領域に printable な語が1つでもあること
        // （制御境界/0x0000/無効スカラー/空白のみでは発火しない）。
        val hasPrintable = (start until marker).any { i ->
            val code = units[i]
            code != 0x0000 &&
                !isControlBoundary(code) &&
                !isInvalidScalar(code) &&
                !Character.isWhitespace(code.toChar())
        }
        if (!hasPrintable) return null
        return decodePrologueUnits(units, start, marker)
    }

    // ------------------------------------------------------------------
    // マーカー走査補助
    // ------------------------------------------------------------------

    // 0x001f (TEXT_RUN_MARKER) の run 開始の構造検証。次に該当するいずれかの場合のみ true:
    //  1. まだ prose が読めていない（先頭）
    //  2. 直前で [rfc0009RecordFooterAt] の RFC 0009 レコードフッターが成立している
    //  3. 前語が 0x001e（インライン終端）または 0x001d（インライン開始）
    //  4. 4語前が 0x001e
    //  5. 直前12語以内（units[index-12..index)）に 0x001c レコード開始マーカーがある
    //  6. 先頭16語以内（index <= 16）かつ先に 0x001c/0x001f が現れていない
    // それ以外（例: 末尾スタイルテーブルの孤立したバイナリ値）は run を開始しない。
    private fun isDocumentTextRunStart(units: IntArray, index: Int, hasProse: Boolean): Boolean {
        if (!hasProse) return true
        if (rfc0009RecordFooterAt(units, index)) return true
        if (index > 0 &&
            (units[index - 1] == DocumentTextConstants.INLINE_TEXT_END ||
                units[index - 1] == DocumentTextConstants.INLINE_TEXT_START)
        ) {
            return true
        }
        if (index >= 4 && units[index - 4] == DocumentTextConstants.INLINE_TEXT_END) return true
        for (i in max(0, index - 12) until index) {
            if (units[i] == DocumentTextConstants.RECORD_START_MARKER) return true
        }
        val headHasMark = (0 until index).any {
            units[it] == DocumentTextConstants.RECORD_START_MARKER ||
                units[it] == DocumentTextConstants.TEXT_RUN_MARKER
        }
        return index <= 16 && !headHasMark
    }

    // RFC 0009 レコードフッターの構造検証:
    // ```text
    // units[index - 3] = len      (レコード全長、語数)
    // units[index - 2] = 0x0000
    // units[index - 1] = class    (既知の RFC 0009 クラスコード)
    // units[index]     = 0x001f   (検証対象マーカー)
    // units[start]     = 0x001c   (開始子、start = index + 1 - len)
    // units[start + 1] = class    (エコー)
    // units[start + 2] = len      (エコー)
    // ```
    private fun rfc0009RecordFooterAt(units: IntArray, index: Int): Boolean {
        if (index < 4 || units[index - 2] != 0x0000) return false
        val clazz = units[index - 1]
        val knownClasses = listOf(
            DocumentTextConstants.RECORD_CLASS_INLINE_CONTEXT,
            DocumentTextConstants.RECORD_CLASS_PARAGRAPH_LINE,
            DocumentTextConstants.RECORD_CLASS_TABLE_SECTION_TRANSITION,
            DocumentTextConstants.RECORD_CLASS_TABLE_CELL,
        )
        if (clazz !in knownClasses) return false
        val totalLength = units[index - 3]
        if (totalLength < 4 || totalLength > index + 1) return false
        val start = index + 1 - totalLength
        return units.getOrNull(start) == DocumentTextConstants.RECORD_START_MARKER &&
            units.getOrNull(start + 1) == clazz &&
            units.getOrNull(start + 2) == totalLength
    }

    // 表示インライン（0x001d）のセレクタ文脈検証。
    // 文脈 [0x001c, 0x0001, 0x0007, 0x0000, 0x0000, sel] で sel ∈ {0x0001, 0x0003, 0x0013} の
    // み [DocumentTextElement.InlineText] として採用し、sel を返す。
    private fun inlineTextSelector(units: IntArray, index: Int): Int? {
        if (index < 6) return null
        val c = index - 6
        if (units[c] == CONTEXT_OPENING &&
            units[c + 1] == CONTEXT_FLAG_1 &&
            units[c + 2] == CONTEXT_FLAG_2 &&
            units[c + 3] == CONTEXT_ZERO &&
            units[c + 4] == CONTEXT_ZERO &&
            units[c + 5] in intArrayOf(0x0001, 0x0003, 0x0013)
        ) {
            return units[c + 5]
        }
        return null
    }

    // スキップインライン（ルビ等）の文脈検証。
    // 前語が 0x001c、または文脈 [0x001c, 0x0001, 0x0007, 0x0000, 0x0001, sel] で sel を返す。
    private fun skippedInlineSelector(units: IntArray, index: Int): Int? {
        if (index == 0) return null
        if (units[index - 1] == CONTEXT_OPENING) return CONTEXT_OPENING
        if (index < 6) return null
        val c = index - 6
        if (units[c] == CONTEXT_OPENING &&
            units[c + 1] == CONTEXT_FLAG_1 &&
            units[c + 2] == CONTEXT_FLAG_2 &&
            units[c + 3] == CONTEXT_ZERO &&
            units[c + 4] == CONTEXT_SKIPPED_FLAG
        ) {
            return units[c + 5]
        }
        return null
    }

    // 表示インライン区間（0x001d 以降〜0x001e/末尾）を読み、[DocumentTextElement.InlineText]
    // を emit して次インデックスを返す。
    private fun pushInlineSegment(
        elements: MutableList<DocumentTextElement>,
        units: IntArray,
        start: Int,
        selector: Int,
    ): Int {
        val text = StringBuilder()
        var index = start + 1
        while (index < units.size) {
            val code = units[index]
            if (code == DocumentTextConstants.INLINE_TEXT_END) {
                if (text.isNotEmpty()) {
                    elements.add(DocumentTextElement.InlineText(selector, text.toString()))
                }
                return index + 1
            }
            if (!isControlBoundary(code) && !isInvalidScalar(code)) {
                text.append(code.toChar())
            }
            index++
        }
        if (text.isNotEmpty()) {
            elements.add(DocumentTextElement.InlineText(selector, text.toString()))
        }
        return index
    }

    // スキップインライン区間（ルビ注記等）を読み、[DocumentTextElement.SkippedInlineText] を
    // 返し、次インデックスを返す。文脈バイト列から 0x001e を含む生バイトを保持する。
    // [SKIPPED_INLINE_MAX_UNITS] 語以内で終端がない場合は null（フォールスルー）。
    private fun readSkippedInlineSegment(
        units: IntArray,
        start: Int,
    ): Pair<DocumentTextElement.SkippedInlineText, Int>? {
        if (start >= units.size || units[start] != DocumentTextConstants.INLINE_TEXT_START) {
            return null
        }
        val contextStart = max(0, start - 6)
        val text = StringBuilder()
        var index = start + 1
        while (index < units.size) {
            if (index - start > SKIPPED_INLINE_MAX_UNITS) return null
            val code = units[index]
            if (code == DocumentTextConstants.INLINE_TEXT_END) {
                val rawBytes = unitsToBeBytes(units, contextStart, index + 1)
                // Rust の SkippedInlineTextSegment::selector() と同一規則:
                // 直前コンテキスト (units[contextStart..start)) の末尾 = units[start - 1]
                val selector = if (start >= 1) units[start - 1] else null
                val segment = DocumentTextElement.SkippedInlineText(selector, text.toString(), rawBytes)
                return Pair(segment, index + 1)
            }
            if (!isControlBoundary(code) && !isInvalidScalar(code)) {
                text.append(code.toChar())
            }
            index++
        }
        return null
    }

    // ------------------------------------------------------------------
    // 共通ヘルパ
    // ------------------------------------------------------------------

    // 制御境界: C0 制御（0x09/0x0a/0x0d を除く）または C1 制御 (0x7f..=0x9f)。
    // 0x09/0x0a/0x0d はプレーンな空白文字として本文に保持される。
    private fun isControlBoundary(code: Int): Boolean =
        (code < 0x20 && code != 0x09 && code != 0x000a && code != 0x0d) || code in 0x7f..0x9f

    // 無効スカラー: サロゲート範囲 (0xd800..=0xdfff) と 0xffff。
    private fun isInvalidScalar(code: Int): Boolean =
        code in 0xd800..0xdfff || code == 0xffff

    // バイト列を big-endian u16 語列に変換（Rust の chunks_exact(2) 相当、末尾の奇数バイトは破棄）。
    private fun toUnits(data: ByteArray): IntArray {
        val count = data.size ushr 1
        val units = IntArray(count)
        for (i in 0 until count) {
            units[i] = ((data[i * 2].toInt() and 0xFF) shl 8) or (data[i * 2 + 1].toInt() and 0xFF)
        }
        return units
    }

    // units[from..to) を big-endian バイト列に変換。
    private fun unitsToBeBytes(units: IntArray, from: Int, to: Int): ByteArray {
        val out = ByteArray((to - from) * 2)
        for (i in from until to) {
            out[(i - from) * 2] = (units[i] ushr 8).toByte()
            out[(i - from) * 2 + 1] = units[i].toByte()
        }
        return out
    }

    // 現在の run を emit し、run を空にする。
    private fun pushRun(elements: MutableList<DocumentTextElement>, run: StringBuilder) {
        if (run.isNotEmpty()) {
            elements.add(DocumentTextElement.TextRun(run.toString()))
            run.clear()
        }
    }

    // data[offset..] が [expected] で始まるか（長さ不足は偽）。
    private fun hasBytesAt(data: ByteArray, offset: Int, expected: ByteArray): Boolean {
        if (offset < 0 || data.size < offset + expected.size) return false
        return expected.indices.all { data[offset + it] == expected[it] }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
