package com.hiyowa.tika.jtd

import org.apache.tika.sax.XHTMLContentHandler
import org.xml.sax.helpers.AttributesImpl
import kotlin.math.max
import kotlin.math.min

/**
 * RFC 0013 流路語彙（Rule Flow）のイベント列。
 *
 * `/DocumentText` のマーカー走査から「罫線で切り刻まれたテキストストリーム」を
 * 意味論的な流路イベント列へ射影する（RFC 0013 §2.1・§9.8 確定版）。
 *
 * | イベント | 実体 | XHTML 射影 |
 * |---|---|---|
 * | [RowHeader] | `0x001c/0x0010 w4=0x008f` 行ヘッダ（Reaffirm） | 行区間の開始 |
 * | [ParagraphHeader] | `0x001c/0x0010`（w4≠0x008f） | `<p>` |
 * | [SpanDeclaration] | `0x001c/0x0030` セルヘッダ（b0,b1） | `<td>`（coalesce 単位） |
 * | [Text] | テキスト run（番号接頭辞・インライン表示テキスト含む） | テキストノード |
 * | [RowAdvance] | `0x000e`（WALL） | `</tr>`（RFC 0013 §3.4 確定的行境界） |
 * | [Wrap] | `0x000a`（WRAP） | 段落内は `<br/>`／セル内は半角スペース平坦化（§12.4 T3-1・前後テキストなしは破棄） |
 * | [SectionTransition] | class `0x0020` | `</table>` close |
 *
 * standalone `0x000c` は RFC 0013 §2.1 のとおり v1 ではイベント化しない
 * （LEN 語／表組構造語／stride-2 列挙表に分解されるため reserved）。
 * フッター検証 `(len, 0x0000, class, 0x001f)` を通らない `0x001c`（偽出現・連番語列）は
 * イベント化せず素通りする（RFC 0009・§5.1 フォールスルー規定）。
 */
internal sealed interface FlowEvent {

    /** FE 壁様式エントリ（RFC 0013 追補 §20.11 登記相当の観測射影）。 */
    data class WallRule(
        val orientation: EdgeOrientation,
        val weight: EdgeWeight,
        val side: EdgeSide = EdgeSide.Unknown,
    ) : FlowEvent

    /** class 0x0010 段落ヘッダ（w4≠0x008f の段落整形系）。 */
    data class ParagraphHeader(val w4: Int) : FlowEvent

    /**
     * class 0x0010 `w4=0x008f` 行ヘッダ（Span Reaffirm 列）。
     * [declaredSpanCount] はペイロード先頭語 w5 の算術（RFC 0013 §3.1）
     * $w_5 = 4(n-1)+3$ から復元する宣言スパン数 n。w5 が算術に合わない場合は null。
     */
    data class RowHeader(val w4: Int, val declaredSpanCount: Int?) : FlowEvent

    /**
     * class 0x0030 セルヘッダ（Span Reaffirm）。b0/b1 は進行軸相対の境界座標。
     * [flags] は語6 のセル状態語（実測: 0x0001=完結セル、0x0000=断片、0x00ff=標準）。
     * [flags2] は語7（0x0002=下方継続アンカー）。RFC 0013 追補 §14 登記。
     */
    data class SpanDeclaration(
        val left: Int,
        val right: Int,
        val flags: Int = 0x00ff,
        val flags2: Int = 0x0000,
    ) : FlowEvent

    /** 可視テキスト（run・自動番号接頭辞・インライン表示テキスト）。 */
    data class Text(val text: String) : FlowEvent

    /** 0x000e 行境界（Row Advance）。 */
    data object RowAdvance : FlowEvent

    /** 0x000a 流路折返し（Stream Wrap）。 */
    data object Wrap : FlowEvent

    /** class 0x0020 表→段落セクション遷移（Flow Terminate）。 */
    data object SectionTransition : FlowEvent
}

internal enum class EdgeOrientation { Vertical, Horizontal }

internal enum class EdgeWeight { Thin, Thick }

/** 辺位相（RFC 0013 追補 §20.13 bind。上下は個別未決のため Unknown）。 */
internal enum class EdgeSide { Leading, Trailing, Top, Bottom, Unknown }

/**
 * [RuleFlowParser] の流路イベント列から合成される出力ブロック（RFC 0013 §3 の骨格）。
 * SAX エミッタはこれを素直に walk するだけで `<p>` / `<table>` を emit できる。
 */
internal sealed interface FlowBlock {

    /** 段落ブロック（`<p>`）。[parts] の [FlowPart.Break] は `<br/>` に射影する。 */
    data class Paragraph(val parts: List<FlowPart>) : FlowBlock

    /** 表ブロック（`<table><tbody>…</tbody></table>`）。 */
    data class Table(val rows: List<FlowRow>) : FlowBlock
}

/** 段落・セル内のインライン構成要素。 */
internal sealed interface FlowPart {
    data class Text(val text: String) : FlowPart

    /** セル内 WRAP 平坦化用（XHTML では半角スペース。§12.4 T3-1）。 */
    data object Break : FlowPart

    /**
     * 縦継続セル統合の継ぎ目（RFC 0013 追補 §13.2。XHTML では `<br/>`）。
     * [Break]（同一セル内折返し）と異なり、物理行統合の論理継ぎ目を表す。
     */
    data object LineBreak : FlowPart
}

/** 論理行（`<tr>`）。物理 tr は WALL 区間と 1:1（RFC 0013 §3.4）。 */
internal data class FlowRow(val cells: List<FlowCell>)

/**
 * 物理セル（`<td>`）。
 * [colspan] はグリッド左辺座標の和集合から算出する近似値（RFC 0013 §5.3 XHTML 側）。
 * 空セル補間（padding）は [parts] が空の [FlowCell] として現れる。
 */
internal data class FlowCell(
    val left: Int,
    val right: Int,
    val colspan: Int,
    val parts: List<FlowPart>,
    val rowspan: Int = 1,
)

/**
 * RFC 0013 流路パーサー（Phase 0 レコード解釈層の流路射影）。
 *
 * `/DocumentText` 生バイト列（SsmgV.01 ヘッダ込み可）を UTF-16BE 語列として走査し、
 * RFC 0009 レコード（フッター検証通過分）と制御境界から [FlowEvent] 列を作る。
 * 平文抽出（[DocumentTextParser.parseDocumentText]）には手を入れず、並行パスとして実装する。
 */
internal object RuleFlowParser {

    // 移植元: DocumentTextParser のプライベート定数（同一値で参照するため再定義）。
    private val MAGIC: ByteArray = "SsmgV.01".toByteArray(Charsets.ISO_8859_1)
    private val SEGMENT_NAME: ByteArray = "TextV.01".toByteArray(Charsets.ISO_8859_1)
    private const val SSMG_HEADER_WORDS = 10
    private const val TEXT_CONTENT_HEADER_WORDS = 16
    private const val CONTENT_UNIT_COUNT_OFFSET = 28
    private const val RAW_TEXT_SEGMENT_COUNT = 0x0001
    private const val ROW_HEADER_W4 = 0x008f
    /** §20.13 bind 済みの壁マーカー id。内部グリッドマーカーは非発射。 */
    private val WALL_MARKER_IDS = setOf(0x01, 0x03, 0x09, 0x0C, 0x0E, 0x12, 0x96)
    // 0x000a 流路折返し（RFC 0013 §2.1 Stream Wrap。DocumentTextConstants に定数がないため定義）。
    private const val WRAP_CODE = 0x000a

    // RFC 0009 インラインセレクタ文脈の固定語列。
    private const val CONTEXT_OPENING = 0x001c
    private const val CONTEXT_FLAG_1 = 0x0001
    private const val CONTEXT_FLAG_2 = 0x0007
    private const val CONTEXT_ZERO = 0x0000
    private const val CONTEXT_SKIPPED_FLAG = 0x0001

    /**
     * DocumentText ストリーム（または枠内 mini DocumentText span・salvage 連結 blob）を
     * 流路イベント列へ射影する。
     *
     * salvage 経路（[DocumentTextParser.embeddedDocumentText]）の bytes は SsmgV.01 断片を
     * `[0x00,0x00]` 区切りで連結した blob であり、単一ストリームとして `documentTextUnitLimit`
     * で区切ると 2 番目以降の断片が全滅するため、語アラインの "SsmgV.01" マジックが
     * 2 箇所以上ある場合は断片毎に [parseSingleStream] してイベント列を連結する
     * （断片毎に [DocumentTextParser.NumberingState] が新規生成される）。
     * 先頭に単一の "SsmgV.01" のみ（通常の 1 ストリーム文書）は従来どおり単一経路。
     */
    fun parse(data: ByteArray): List<FlowEvent> {
        val starts = magicWordOffsets(data)
        if (starts.size >= 2) {
            val events = mutableListOf<FlowEvent>()
            for (k in starts.indices) {
                val end = if (k + 1 < starts.size) starts[k + 1] else data.size
                val seg = if (starts[k] == 0 && end == data.size) data else data.copyOfRange(starts[k], end)
                if (seg.size < 4) continue
                events.addAll(parseSingleStream(seg))
            }
            return events
        }
        return parseSingleStream(data)
    }

    /**
     * data 中の語アライン（バイト offset 偶数）の "SsmgV.01" マジック出現位置（バイト offset）を
     * 列挙する。先頭のみ・単一 magic は 1 要素（呼び出し側は単一経路に落ちる）。
     * 断片間は最低でも `[0x00,0x00]` 区切りを挟むため、一致時は magic 長分前進で十分。
     */
    private fun magicWordOffsets(data: ByteArray): List<Int> {
        if (data.size < MAGIC.size + 2) return emptyList()
        val hits = mutableListOf<Int>()
        var i = 0
        val last = data.size - MAGIC.size
        while (i <= last) {
            var match = true
            for (j in MAGIC.indices) {
                if (data[i + j] != MAGIC[j]) {
                    match = false
                    break
                }
            }
            if (match) {
                if (i % 2 == 0 && i + MAGIC.size + 2 <= data.size) hits.add(i)
                i += MAGIC.size
            } else {
                i++
            }
        }
        return hits
    }

    /**
     * 単一 DocumentText ストリームを流路イベント列へ射影する。
     * 先頭マーカーより手前の raw 前置き（P1 混在型）は [FlowEvent.Text] として先頭に置く。
     * raw-text 単一セグメント（TextV.01）は単一の [FlowEvent.Text] に射影する。
     */
    private fun parseSingleStream(data: ByteArray): List<FlowEvent> {
        val units = toUnits(data)
        // raw-text 単一セグメント（SsmgV.01 + TextV.01 + w[9]==1 / マーカーレス）は本文を
        // 単一 Text に射影し、マーカー走査に入らない（parseDocumentText と同一ゲート）。
        if (data.startsWith(MAGIC) &&
            hasBytesAt(data, SSMG_HEADER_WORDS * 2, SEGMENT_NAME) &&
            (units.getOrNull(9) == RAW_TEXT_SEGMENT_COUNT || isMarkerlessRawTextSpan(units))
        ) {
            return parseRawText(units)
        }

        val unitLimit = min(documentTextUnitLimit(data) ?: units.size, units.size)
        val events = mutableListOf<FlowEvent>()
        val txt = StringBuilder()
        var reading = false
        val numberingState = DocumentTextParser.NumberingState()

        // 混在型（P1）: 最初のマーカーより手前の raw 前置きを、後続マーカー本文より前で
        // Text として1つ emit する。[DocumentTextParser.parseDocumentText] の
        // decodeRawPrologue と同一条件（マーカー型ファイルは発火せず、通常走査のみ・
        // 現状と同一の出力を作るリグレッションガード）。
        rawPrologueText(data, units)?.let { events.add(FlowEvent.Text(it)) }
        // TextV.01 名なし前置きも RFC 0013 追補 §13.1 契約に従い、最初のマーカー前から復元する。
        markerPathPrologueText(data, units)?.let { events.add(FlowEvent.Text(it)) }

        var index = 0
        while (index < unitLimit) {
            val code = units[index]
            when (code) {
                DocumentTextConstants.RECORD_START_MARKER -> {
                    // 0x001c は先に検証されたレコードとして消費を試み、成功時のみ宣言語とする
                    // （split-then-join なり偽 0x001c でセーフティハウリングするため禁止）。
                    val recordEnd = validateRecord(units, index)
                    if (recordEnd != null) {
                        flushText(events, txt)
                        val clazz = units[index + 1]
                        when (clazz) {
                            DocumentTextConstants.RECORD_CLASS_TABLE_CELL ->
                                events.add(
                                    FlowEvent.SpanDeclaration(
                                        units[index + 4],
                                        units[index + 5],
                                        units.getOrElse(index + 6) { 0x00ff },
                                        units.getOrElse(index + 7) { 0x0000 },
                                    ),
                                )
                            DocumentTextConstants.RECORD_CLASS_TABLE_SECTION_TRANSITION ->
                                events.add(FlowEvent.SectionTransition)
                            DocumentTextConstants.RECORD_CLASS_PARAGRAPH_LINE -> {
                                val w4 = units[index + 4]
                                if (w4 == ROW_HEADER_W4) {
                                    events.add(
                                        FlowEvent.RowHeader(w4, declaredSpanCount(units[index + 5])),
                                    )
                                } else {
                                    events.add(FlowEvent.ParagraphHeader(w4))
                                }
                            }
                            // class 0x0000（インライン文脈）はイベントなし（reading のみ更新）。
                            else -> Unit
                        }
                        // 自動番号の平文互換: 終端語で接頭辞を算出し、次テキストの先頭に置く。
                        DocumentTextParser.paragraphHeaderPrefix(units, recordEnd, numberingState)
                            ?.let { txt.append(it) }
                        reading = true
                        index = recordEnd + 1
                        continue
                    }
                    // 検証失敗: 1 語だけ進み通常走査に復す（0x001c 自体は C0 制御境界として扱う）。
                    flushText(events, txt)
                    reading = false
                    index++
                }

                DocumentTextConstants.TEXT_RUN_MARKER -> {
                    // 記録終端以外の裸 0x001f: run 開始。
                    flushText(events, txt)
                    DocumentTextParser.paragraphHeaderPrefix(units, index, numberingState)
                        ?.let { txt.append(it) }
                    reading = true
                    index++
                }

                DocumentTextConstants.TEXT_ROW_DELIMITER -> {
                    // 0x000e Row Advance（RFC 0009: reading は跨いだまま維持）。
                    flushText(events, txt)
                    events.add(FlowEvent.RowAdvance)
                    reading = true
                    index++
                }

                WRAP_CODE -> {
                    // 0x000a Stream Wrap（reading は維持）。
                    flushText(events, txt)
                    events.add(FlowEvent.Wrap)
                    reading = true
                    index++
                }

                DocumentTextConstants.DOCUMENT_TEXT_PAGE_BREAK_CONTROL -> {
                    // 0x000c standalone BREAK: v1 ではイベント化しない（reading は維持）。
                    flushText(events, txt)
                    reading = true
                    index++
                }

                DocumentTextConstants.DOCUMENT_TEXT_INLINE_SPACE_CONTROL -> {
                    // 0x0010 インラインスペース制御（reading は維持）。
                    flushText(events, txt)
                    reading = true
                    index++
                }

                DocumentTextConstants.INLINE_TEXT_START -> {
                    // 0x001d インライン開始: 以降は 0x001f で reading を再開する。
                    index = handleInline(units, index, events, txt)
                    reading = false
                }

                else -> {
                    // FE0X 系インライン規則領域はテキスト再開点まで消費し、reading を維持する。
                    // 発火は reading 中のみ（reading=false 時の再開は本文収集を伴わないため
                    // 従来挙動へフォールバック）。
                    if (reading && DocumentTextParser.isInlineRuleRegionMarker(code)) {
                        val resume = DocumentTextParser.inlineRuleRegionResume(units, index, unitLimit)
                        if (resume != null) {
                            index = resume
                            continue
                        }
                    }
                    if (isControlBoundary(code) || isInvalidScalar(code)) {
                        // サロゲート / 0xFFFF / C0・C1 制御境界: run 切断。
                        flushText(events, txt)
                        reading = false
                    } else if (reading) {
                        txt.append(code.toChar())
                    }
                    index++
                }
            }
        }
        flushText(events, txt)
        val fences = mutableListOf<Int>()
        for (k in data.indices) {
            if (k > 0 && (data[k].toInt() and 0xFF) == 0xFE) fences.add(k)
        }
        for (m in fences.indices) {
            val fence = fences[m]
            val id = data[fence - 1].toInt() and 0xFF
            if (id !in WALL_MARKER_IDS) continue
            val blockEnd = if (m + 1 < fences.size) fences[m + 1] - 1 else data.size
            var j = fence + 1
            while (j + 3 < blockEnd) {
                val axis = data[j].toInt() and 0xFF
                if (axis in 0x01..0x03 && (data[j + 1].toInt() and 0xFF) == 0x02) {
                    val style = ((data[j + 2].toInt() and 0xFF) shl 8) or
                        (data[j + 3].toInt() and 0xFF)
                    j += 4
                    val orientation = when (axis) {
                        0x02 -> EdgeOrientation.Vertical
                        0x03 -> EdgeOrientation.Horizontal
                        else -> continue
                    }
                    val weight = if (style == 0x0003) EdgeWeight.Thick else EdgeWeight.Thin
                    val side = if (orientation == EdgeOrientation.Vertical) {
                        when (id) {
                            0x09 -> EdgeSide.Leading
                            0x01 -> EdgeSide.Trailing
                            else -> EdgeSide.Unknown
                        }
                    } else {
                        EdgeSide.Unknown
                    }
                    events.add(FlowEvent.WallRule(orientation, weight, side))
                } else {
                    j++
                }
            }
        }
        return events
    }

    // raw-text 単一セグメントの本文を単一 Text に射影（0x0000 で打ち切り、無効スカラーはスキップ）。
    private fun parseRawText(units: IntArray): List<FlowEvent> {
        val length = units.getOrNull(TEXT_CONTENT_HEADER_WORDS - 1) ?: return emptyList()
        val textStart = TEXT_CONTENT_HEADER_WORDS
        val textEnd = min(max(0, textStart + length), units.size)
        val sb = StringBuilder()
        for (i in textStart until textEnd) {
            val code = units[i]
            if (code == 0x0000) break
            if (!isInvalidScalar(code)) {
                sb.append(code.toChar())
            }
        }
        return if (sb.isEmpty()) emptyList() else listOf(FlowEvent.Text(sb.toString()))
    }

    // 混在型（P1）前置き: TextV.01 span の最初のマーカー手前まで raw デコードし、
    // 前置きテキスト（CR/LF 保持・0x0000 はスキップ継続・制御境界/無効スカラーで打ち切り）を
    // 返す。[DocumentTextParser.decodeRawPrologue] と同一条件:
    // マジック＋TextV.01 名が揃い units[14]==0x0000（長さフィールドが本文長を指す）、
    // spanLength が妥当、かつ最初のマーカーが span 先頭でなく・先頭に printable な語がある
    // ときのみ発火（マーカー型ファイルは発火しない）。
    private fun rawPrologueText(data: ByteArray, units: IntArray): String? {
        if (!data.startsWith(MAGIC)) return null
        if (!hasBytesAt(data, SSMG_HEADER_WORDS * 2, SEGMENT_NAME)) return null
        if (units.size < TEXT_CONTENT_HEADER_WORDS || units[14] != 0x0000) return null
        val spanLength = units[15]
        if (spanLength == 0 || spanLength > units.size - TEXT_CONTENT_HEADER_WORDS) return null
        val start = TEXT_CONTENT_HEADER_WORDS
        val marker = firstMarker(units, start, start + spanLength) ?: return null
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
        return prologueUnits(units, start, marker)
    }

    // TextV.01 名なし型: Ssmg ヘッダ直後から最初の RFC 0009 マーカーまでを本文として復元する。
    // RFC 0013 追補 §13.1 の契約に基づき、不審語があれば前置き全体を採用しない。
    private fun markerPathPrologueText(data: ByteArray, units: IntArray): String? {
        if (!data.startsWith(MAGIC)) return null
        if (hasBytesAt(data, SSMG_HEADER_WORDS * 2, SEGMENT_NAME)) return null
        val start = SSMG_HEADER_WORDS
        val marker = firstMarker(units, start, units.size) ?: return null
        if (marker <= start) return null

        val text = StringBuilder()
        for (index in start until marker) {
            val code = units[index]
            when (code) {
                0x0000 -> continue
                0x000d, 0x000a -> break
                else -> {
                    if (!isPlausibleVisibleTextWord(code)) return null
                    text.append(code.toChar())
                }
            }
        }
        return text.toString().takeUnless { it.isBlank() }
    }

    // DocumentTextParser の可視テキスト語判定と同一条件（同関数は private）。
    private fun isPlausibleVisibleTextWord(code: Int): Boolean =
        (code in 0x3000..0x9FFF || code in 0xFF01..0xFF5E || code in 0x21..0x7E || code == 0x20) &&
            code !in 0xFE00..0xFE0F

    // units[from..to) 内の最初の RFC 0009 マーカー（0x001c/0x001d/0x001f）の位置。
    // すべてマーカーレスなら null（[DocumentTextParser.firstTextMarker] と同一）。
    private fun firstMarker(units: IntArray, from: Int, to: Int): Int? {
        for (i in from until to) {
            if (units[i] == DocumentTextConstants.RECORD_START_MARKER ||
                units[i] == DocumentTextConstants.INLINE_TEXT_START ||
                units[i] == DocumentTextConstants.TEXT_RUN_MARKER
            ) return i
        }
        return null
    }

    // prologueUnits: [DocumentTextParser.decodePrologueUnits] と同一規則（0x0000 スキップ継続・
    // CR/LF 保持・制御境界/無効スカラーで打ち切り・全くなければ null を返す）。
    private fun prologueUnits(units: IntArray, start: Int, end: Int): String? {
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
        return if (text.isEmpty()) null else text.toString()
    }

    // マーカーレス raw テキスト型（w[9] が 0x0001 より大きい実原本）。本文領域に RFC 0009
    // マーカーが一切含まれないときのみ true。
    private fun isMarkerlessRawTextSpan(units: IntArray): Boolean {
        if (units.size < TEXT_CONTENT_HEADER_WORDS || units[14] != 0x0000) return false
        val length = units[15]
        if (length == 0 || length > units.size - TEXT_CONTENT_HEADER_WORDS) return false
        return (TEXT_CONTENT_HEADER_WORDS until TEXT_CONTENT_HEADER_WORDS + length).all { i ->
            units[i] != DocumentTextConstants.RECORD_START_MARKER &&
                units[i] != DocumentTextConstants.INLINE_TEXT_START &&
                units[i] != DocumentTextConstants.TEXT_RUN_MARKER
        }
    }

    // 本文語数上限（SsmgV.01 + TextV.01 ヘッダが揃う場合のみ u32 BE 語数 + 16、それ以外は null）。
    private fun documentTextUnitLimit(data: ByteArray): Int? {
        if (!data.startsWith(MAGIC)) return null
        if (data.size < TEXT_CONTENT_HEADER_WORDS * 2) return null
        if (!hasBytesAt(data, SSMG_HEADER_WORDS * 2, SEGMENT_NAME)) return null
        val offset = CONTENT_UNIT_COUNT_OFFSET
        val count = ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
        return TEXT_CONTENT_HEADER_WORDS + count
    }

    // RFC 001c レコードの検証に成功した場合は終端 0x001f の位置（j）を、失敗時は null を返す。
    // フッター `(j-3)=len, (j-2)=0x0000, (j-1)=class, (j)=0x001f` とインライン区間構文を検証。
    private fun validateRecord(units: IntArray, i: Int): Int? {
        if (i + 2 >= units.size) return null
        val clazz = units[i + 1]
        if (clazz != DocumentTextConstants.RECORD_CLASS_INLINE_CONTEXT &&
            clazz != DocumentTextConstants.RECORD_CLASS_PARAGRAPH_LINE &&
            clazz != DocumentTextConstants.RECORD_CLASS_TABLE_SECTION_TRANSITION &&
            clazz != DocumentTextConstants.RECORD_CLASS_TABLE_CELL
        ) {
            return null
        }
        val len = units[i + 2]
        if (len < 8) return null
        if (i + len > units.size) return null
        val j = i + len - 1
        if (units[j] != DocumentTextConstants.TEXT_RUN_MARKER) return null
        if (units[j - 1] != clazz) return null
        if (units[j - 2] != 0x0000) return null
        if (units[j - 3] != len) return null
        // 本体の 0x001d は直後 0x001e と対になるインライン区間開閉のときのみ拒否する
        // （RFC 0009 §誤検出の連番語列ガード）。列領域語 w5 等が 0x001d と同値な
        // 正当座標語なら承認する（行間区切りヘッダ len=0x0029/w5=0x001d 形）。
        for (k in (i + 3) until (j - 3)) {
            if (units[k] == DocumentTextConstants.INLINE_TEXT_START &&
                units.getOrNull(k + 1) == DocumentTextConstants.INLINE_TEXT_END
            ) {
                return null
            }
        }
        return j
    }

    // 行ヘッダ w4=0x008f の宣言スパン数（RFC 0013 §3.1: $w_5 = 4(n-1)+3$ の算術逆）。
    private fun declaredSpanCount(w5: Int): Int? {
        if (w5 >= 3 && (w5 - 3) % 4 == 0) return (w5 - 3) / 4 + 1
        return null
    }

    // 0x001d インライン開始の処理。可視区間（sel ∈ {0x0001,0x0003,0x0013}）は [FlowEvent.Text]
    // として、スキップ区間（flag=0x0001）は読み捨てで 0x001e の次へ、それ以外は 1 語スキップ。
    private fun handleInline(
        units: IntArray,
        index: Int,
        events: MutableList<FlowEvent>,
        txt: StringBuilder,
    ): Int {
        flushText(events, txt)
        if (index >= 6) {
            val c = index - 6
            if (units[c] == CONTEXT_OPENING &&
                units[c + 1] == CONTEXT_FLAG_1 &&
                units[c + 2] == CONTEXT_FLAG_2 &&
                units[c + 3] == CONTEXT_ZERO
            ) {
                val visible = units[c + 4] == CONTEXT_ZERO &&
                    units[c + 5] in intArrayOf(0x0001, 0x0003, 0x0013)
                val skipped = units[c + 4] == CONTEXT_SKIPPED_FLAG
                if (visible || skipped) {
                    val (text, next) = readInlineInterval(units, index)
                    if (visible && text.isNotEmpty()) {
                        events.add(FlowEvent.Text(text))
                    }
                    return next
                }
            }
        }
        return index + 1
    }

    // 0x001d..0x001e 区間を読み、(可視テキスト, 0x001e の次の位置) を返す。
    private fun readInlineInterval(units: IntArray, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var index = start + 1
        while (index < units.size) {
            val code = units[index]
            if (code == DocumentTextConstants.INLINE_TEXT_END) return sb.toString() to (index + 1)
            if (!isControlBoundary(code) && !isInvalidScalar(code)) {
                sb.append(code.toChar())
            }
            index++
        }
        return sb.toString() to index
    }

    // 現在のテキストバッファを [FlowEvent.Text] として emit し、バッファを空にする。
    private fun flushText(events: MutableList<FlowEvent>, txt: StringBuilder) {
        if (txt.isNotEmpty()) {
            events.add(FlowEvent.Text(txt.toString()))
            txt.setLength(0)
        }
    }

    // 制御境界: C0 制御（0x09/0x0a/0x0d を除く）または C1 制御 (0x7f..=0x9f)。
    // 0x09/0x0d は本文に保持される（0x0a は Wrap として先に消費される）。
    private fun isControlBoundary(code: Int): Boolean =
        (code < 0x20 && code != 0x09 && code != 0x000a && code != 0x0d) || code in 0x7f..0x9f

    // 無効スカラー: サロゲート範囲 (0xd800..=0xdfff) と 0xffff。
    private fun isInvalidScalar(code: Int): Boolean =
        code in 0xd800..0xdfff || code == 0xffff

    // バイト列を big-endian u16 語列に変換（末尾の奇数バイトは破棄）。
    private fun toUnits(data: ByteArray): IntArray {
        val count = data.size ushr 1
        val units = IntArray(count)
        for (i in 0 until count) {
            units[i] = ((data[i * 2].toInt() and 0xFF) shl 8) or (data[i * 2 + 1].toInt() and 0xFF)
        }
        return units
    }

    // data[offset..] が [expected] で始まるか（長さ不足は偽）。
    private fun hasBytesAt(data: ByteArray, offset: Int, expected: ByteArray): Boolean {
        if (offset < 0 || data.size < offset + expected.size) return false
        return expected.indices.all { data[offset + it] == expected[it] }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}

/**
 * RFC 0013 §3 表状態機械（v1 確定版）。
 *
 * 流路イベント列から [FlowBlock] 列（段落と表の骨格）を合成する。
 *
 * 規則（RFC 0013 §3.3〜§3.6・§9.7/§9.8 確定事項）:
 * 1. WALL（[FlowEvent.RowAdvance]）区間 = 物理 `<tr>`（確定的行境界）。
 * 2. 区間内で同一スパン $[b_0,b_1]$ の反復宣言（Span Reaffirm）は同一セルに coalesce
 *    （テキストは [FlowPart.Break] 区切りで連結）。
 * 3. 境界ストリップ（$b_1-b_0 \le 4$）でかつテキストを運ばないセルのみを
 *    コンテンツセルから除外（テキストを伴う狭幅セルは保持し §2.2 を守る）。
 * 4. 表 open: セルヘッダ 2 個以上の行に `w4=0x008f` 行ヘッダが付く場合、または
 *    同一 left 座標列（安定グリッド）が連続行で反復する場合。
 *    - 誤検出ガード: 単一スパン行（coalesce 後 1 セル）では開かない／WALL 単独では開かない。
 * 5. 表 close: [FlowEvent.SectionTransition] / 表組外テキスト区間 / ストリーム終（EOS flush）。
 *    段落ヘッダは表を suspend 状態で維持し、RFC 0013 §12.4 T3-2 に従い即時 close しない。
 * 6. 同一スパン署名の物理行対を縦継続統合するのは、両行が各自 RowHeader
 *    （`0x001c/0x0010` w4=0x008f）を持つ場合に限る。共有ヘッダ後のヘッダなし WALL 行
 *    （ヘッダなしWALL行型）は §3.4 の独立 `<tr>` を維持する（RFC 0013 追補 §13.2）。
 *    閉じた非空白テキストセルへの後続テキスト開始があれば別論理行とし、
 *    WRAP 末尾セルおよび空セルからの継続のみ統合する（RFC 0013 追補 §14 R2）。
 *    これは flowstruct（T3-1）の通常折返しを保ちつつ、追補の明示的継続だけを統合する。
 * 7. §14 R3: 真包含する広幅宣言へ空白の狭幅罫断片を折り込む。
 * 8. §14 R4/R5: 語7 下方継続アンカーと直下断片を吸収し、ブロック列を rowspan/colspan 化する。
 * 9. 正規化: グリッド左辺座標の和集合で列槽を決め、欠落槽は空セルで補間（padding）。
 *    跨ぎセルは colspan 近似、欠落スロットは rowspan 近似（RFC 0013 §12.4 T3-3）。
 *    Markdown 平坦化は Tika serializer の非対称性により §5.2 の対象外。
 */
internal object RuleFlowAssembler {

    // 境界ストリップの幅閾（$b_1 - b_0 \le 4$、RFC 0013 §3.3）。
    private const val BOUNDARY_STRIP_WIDTH = 4
    // §14.5: テキスト巻き上げは狭幅ブロック列に限定し、広幅列は行単位で保持する。
    private const val BLOCK_COL_MAX_WIDTH = 16

    /** 現セグメント内のセル書きかけ（coalesce 中の同一スパンが同一インスタンスに残る）。 */
    private data class CellDraft(
        var left: Int,
        var right: Int,
        val parts: MutableList<FlowPart>,
        var flags: Int = 0x00ff,
        var flags2: Int = 0x0000,
    ) {
        /** normalizeCell の Break 除去前に記録する縦継続シグナル。 */
        var endedWithBreak = false
        /**
         * 可視テキスト（[FlowPart.Text] 部分）がすべて空白か。
         * [FlowPart.Break] は空白扱いなので無視してよい（一文字幅の狭幅セルでも
         * テキストを運んでいれば非 blank となり保持対象になる）。
         */
        fun isBlank(): Boolean = parts.filterIsInstance<FlowPart.Text>().joinToString("").isBlank()
    }

    /** 流路イベント列を段落・表ブロック列へ合成する。 */
    fun assemble(events: List<FlowEvent>): List<FlowBlock> {
        val blocks = mutableListOf<FlowBlock>()
        val paraParts = mutableListOf<FlowPart>()
        val segmentCells = mutableListOf<CellDraft>()
        var segmentHeader: FlowEvent.RowHeader? = null
        var current: CellDraft? = null
        // null = 表コンテキスト外（表 open まで保持されない行は pending に預ける）。
        var tableRows: MutableList<List<CellDraft>>? = null
        val pending = mutableListOf<List<CellDraft>>()
        var openLogical: MutableList<CellDraft>? = null
        var openContinued = false
        // RFC 0013 追補 §16v2: 縦書き band（行ヘッダ無し WRAP-open 行）の物理行バッファ。
        var bandBuffer: MutableList<List<CellDraft>>? = null
        // 追補 §16v2: band 再構成が確定した tall span 強制指定（tableRows 絶対行index→cellindex→rowspan）。
        val bandPreSpans = mutableMapOf<Pair<Int, Int>, Int>()

        fun rowSig(cells: List<CellDraft>): List<Pair<Int, Int>> = cells.map { it.left to it.right }

        // 追補 §13.2/§16.1: 継続信号の付与は本文+WRAP-open のマス（従来契約）。
        // 本文空白でも WRAP-open が残る行（時割選択様式の埋め立て白紙行）は
        // 継続を「殺さない」（living なら維持・非 living から复活はしない）。
        fun rowContinues(cells: List<CellDraft>): Boolean =
            cells.any { it.endedWithBreak && !it.isBlank() }

        fun hasText(cell: CellDraft): Boolean =
            cell.parts.filterIsInstance<FlowPart.Text>().joinToString("") { it.text }.isNotBlank()

        /** living 更新: 付与（本文+WRAP）／埋め立て白紙（WRAP-open 継続・閉塞本文無し）は living 維持。 */
        fun livingNext(prev: Boolean, cells: List<CellDraft>): Boolean =
            rowContinues(cells) ||
                (prev && cells.any { it.endedWithBreak } && cells.none { hasText(it) && !it.endedWithBreak })

        /** 追補 §15B 継続字下げ判定: セル内非空テキストが行頭空白類で始まるか。 */
        fun beginsIndented(cell: CellDraft): Boolean =
            cell.parts.filterIsInstance<FlowPart.Text>().joinToString("") { it.text }.let { t ->
                t.isNotEmpty() && t.first().isWhitespace()
            }

        /**
         * 追補 §15B 字下げ継続行判定: 全非空セルが同一スパンの先行非空セルへの
         * 字下げ継続テキストである（かつ 1 つ以上該当）。字下げのない新テキスト・
         * 未宣言スパン・先行側が空のセルが 1 つでもあれば非継続（新論理行）。
         */
        fun isIndentedContinuationRow(logical: List<CellDraft>, cells: List<CellDraft>): Boolean {
            var any = false
            for (c in cells) {
                val t = c.parts.filterIsInstance<FlowPart.Text>().joinToString("") { it.text }
                if (t.isBlank()) continue
                val target = logical.firstOrNull { it.left == c.left && it.right == c.right }
                if (target == null) {
                    return false
                }
                val tt = target.parts.filterIsInstance<FlowPart.Text>().joinToString("") { it.text }
                if (tt.isBlank()) return false
                if (!beginsIndented(c)) return false
                any = true
            }
            return any
        }

        fun flushLogical() {
            val row = openLogical ?: return
            val flushed = row.filter { (it.right - it.left) > BOUNDARY_STRIP_WIDTH || !it.isBlank() }
            if (flushed.isNotEmpty()) tableRows?.add(flushed)
            openLogical = null
            openContinued = false
        }

        /** 追補 §16v2 開宣言: 現行行のいずれかのマスが WRAP 終端（縦書きマスが下へ開いている）。 */
        fun rowWrapOpen(cells: List<CellDraft>): Boolean = cells.any { it.endedWithBreak }

        /** 追補 §16v2 band クローズ: バッファ物理行を可視行列へ再構成して tableRows へ引き渡す。 */
        /** 追補 §16v2 band クローズ: バッファ物理行を可視行列へ再構成して tableRows へ引き渡す。
         *  tall span 強制指定は tableRows 絶対行indexへ変換して bandPreSpans に蓄積する。 */
        fun closeBand() {
            val band = bandBuffer ?: return
            bandBuffer = null
            if (tableRows == null) return
            val base = tableRows!!.size
            val (rows, forced) = reconstructBand(band)
            rows.forEach { tableRows!!.add(it) }
            forced.forEach { (k, s) -> bandPreSpans[base + k.first to k.second] = s }
        }

        fun mergeIntoLogical(logical: MutableList<CellDraft>, content: List<CellDraft>) {
            val bySpan = logical.associateBy { it.left to it.right }
            for (cell in content) {
                val target = bySpan[cell.left to cell.right]
                if (target == null) {
                    logical.add(cell)
                    continue
                }
                val targetHasText = !target.isBlank()
                val contHasText = !cell.isBlank()
                when {
                    targetHasText && contHasText -> {
                        target.parts.add(FlowPart.LineBreak)
                        target.parts.addAll(cell.parts)
                    }
                    !targetHasText && contHasText -> {
                        target.parts.clear()
                        target.parts.addAll(cell.parts)
                    }
                    else -> Unit
                }
                target.endedWithBreak = cell.endedWithBreak
            }
        }

        /**
         * 閉じたセル衝突判定（RFC 0013 追補 §14 R2): 後続行が語6=0x0001（完結セル）で、
         * 先行行の閉じた（非空白・WRAP なし）テキストセルへ別テキストを新規宣言するなら衝突。
         * 語6=0x0000/0x00ff 等の継続・断片宣言は月历型折返しとして統合を認める（§13.2 維持）。
         */
        fun mergeConflict(logical: List<CellDraft>, cells: List<CellDraft>): Boolean {
            val bySpan = logical.associateBy { it.left to it.right }
            return cells.any { cont ->
                // 追補 §19: 完結系は bit0（0x0001/0x0003）。標準マス 0x00ff を明示除外
                // して厳密一致 0x0001 の見逃し（完結細身）を解消する。
                cont.flags != 0x00ff && (cont.flags and 0x0001) != 0 && !cont.isBlank() &&
                    bySpan[cont.left to cont.right]?.let { !it.isBlank() && !it.endedWithBreak } == true
            }
        }

        fun commitTable() {
            val rows = tableRows ?: return
            flushLogical()
            closeBand()
            if (rows.isNotEmpty()) {
                blocks.add(buildTable(rows, bandPreSpans))
            }
            rows.clear()
            bandPreSpans.clear()
            tableRows = null
        }

        fun flushPara() {
            // 先頭/末尾の Break・LineBreak を落としてから連結が非空なら Paragraph を append。
            val trimmed = paraParts
            while (trimmed.isNotEmpty() && (trimmed.first() is FlowPart.Break || trimmed.first() is FlowPart.LineBreak)) trimmed.removeAt(0)
            while (trimmed.isNotEmpty() && (trimmed.last() is FlowPart.Break || trimmed.last() is FlowPart.LineBreak)) trimmed.removeAt(trimmed.size - 1)
            if (trimmed.any { it is FlowPart.Text }) {
                if (tableRows != null && trimmed.filterIsInstance<FlowPart.Text>()
                        .joinToString("") { it.text }.isNotBlank()
                ) {
                    commitTable()
                }
                blocks.add(FlowBlock.Paragraph(trimmed.toList()))
            }
            paraParts.clear()
        }

        fun abandonPendingToParagraph() {
            // テキスト保存則: 未確定行の全セル parts を段落バッファへ連結。
            for (row in pending) {
                for (cell in row) {
                    paraParts.addAll(cell.parts)
                }
            }
            pending.clear()
        }

        fun lefts(cells: List<CellDraft>): List<Int> = cells.map { it.left }

        // 追補 §15: 細分列番号ヘッダ行の構造的認識（grid 左辺不一致でも adopting 可）。
        // 多数の細分マス（半幅数字 span）で構成され、非数字本文マスが 2 以下
        // （斜め罫・「学年/時間」表冠ラベル等）の行を列番号ヘッダとみなす。
        fun digitHeaderLike(row: List<CellDraft>): Boolean =
            row.size >= 8 && row.count { c ->
                val t = c.parts.filterIsInstance<FlowPart.Text>().joinToString("") { it.text }
                t.isNotBlank() && t.none { it.isDigit() }
            } <= 2

        var finalizeSegmentCounter = 0

        fun normalizeCell(cell: CellDraft): CellDraft {
            cell.endedWithBreak = cell.endedWithBreak || cell.parts.lastOrNull() is FlowPart.Break
            while (cell.parts.firstOrNull() is FlowPart.Break) cell.parts.removeAt(0)
            while (cell.parts.lastOrNull() is FlowPart.Break) cell.parts.removeAt(cell.parts.lastIndex)
            val normalized = mutableListOf<FlowPart>()
            for (part in cell.parts) {
                if (part !is FlowPart.Break || normalized.lastOrNull() !is FlowPart.Break) {
                    normalized.add(part)
                }
            }
            cell.parts.clear()
            cell.parts.addAll(normalized)
            return cell
        }

        fun finalizeSegment() {
            // 生セル（current）を確定して segmentCells に加算してから判定する。
            current?.let { segmentCells.add(normalizeCell(it)) }
            current = null
            // 境界ストリップ（$b_1-b_0 \le 4$）でかつテキストを運ばないセルのみを除外する。
            // 一文字幅の狭幅セル（記入欄等）でもテキストを伴う限り保持し、
            // 可視テキスト全量保存則（RFC 0013 §2.2）を守る。
            val content = segmentCells.filter { (it.right - it.left) > BOUNDARY_STRIP_WIDTH || !it.isBlank() }
            val allCells = segmentCells.toList()
            when {
                // ストリップのみ（隙間）行: pending の未確定セグメントを
                // テキストを段落へ退避してから消去する（保存則 §2.2）。
                content.isEmpty() -> {
                    flushLogical()
                    closeBand()
                    // RFC 0013 追補 §15: 空区切り行（WALL 重複・分節罫行）は pending を消さない。
                    // 表 open 前の pending ヘッダ行は後続の表と接続されるまで保持する。
                    // 段落退避が必要になるのは EOS（abandonPendingToParagraph）で保証される（保存則 §2.2）。
                    if (tableRows != null) {
                        abandonPendingToParagraph()
                    }
                }
                content.size == 1 -> {
                    if (tableRows != null) {
                        flushLogical()
                        // 全幅の空スパンは孤立罫として通過し、テキストを伴う全幅スパンは
                        // 表内の一行として追加する。
                        if (!content[0].isBlank()) tableRows!!.add(content)
                    } else {
                        // 単一カラム行は表を開かない（誤検出ガード、RFC 0013 §3.5）。
                        // pending のテキストは段落へ退避してから現セルを追記する。
                        abandonPendingToParagraph()
                        paraParts.addAll(content[0].parts)
                        flushPara()
                    }
                }
                else -> {
                    when {
                        tableRows != null -> {
                            val rowHeadered = segmentHeader != null
                            val open = openLogical
                            // RFC 0013 追補 §13.2/§15 拡張: 断片行の span 署名が先行論理行の部分集合
                            // （tall cell 未再宣言・refinement）でも WRAP 継続中なら同一論理行へ吸収する。
                            val sigOk = open != null && rowHeadered &&
                                rowSig(open).toSet().containsAll(rowSig(allCells).toSet())
                            val blankRow = allCells.all { it.isBlank() }
                            val band = bandBuffer
                            // 追補 §16v2: band 終端は「開いたマスが消えた行（WRAP なし）」または行ヘッダ行。
                            // WRAP-open の本文空白行は縦流れの埋め立て（レーンが下へ開いたまま）であり終端しない。
                            if (band != null && (rowHeadered || !rowWrapOpen(allCells))) {
                                flushLogical()
                                closeBand()
                            }
                            val continuedIntoBand = bandBuffer != null && rowWrapOpen(allCells)
                            when {
                                sigOk && open != null && openContinued && !mergeConflict(open, allCells) -> {
                                    mergeIntoLogical(open, allCells)
                                    openContinued = livingNext(openContinued, allCells) && rowHeadered
                                }
                                // 追補 §15B: WRAP 語なし・行頭字下げのみを継続シグナルとする断片行を吸収する。
                                sigOk && open != null && isIndentedContinuationRow(open, allCells).also {
                                } -> {
                                    mergeIntoLogical(open, allCells)
                                    openContinued = livingNext(openContinued, allCells) && rowHeadered
                                }
                                continuedIntoBand -> bandBuffer!!.add(allCells.toList())
                                !rowHeadered && !blankRow && rowWrapOpen(allCells) -> {
                                    flushLogical()
                                    bandBuffer = mutableListOf(allCells.toList())
                                }
                                blankRow -> flushLogical() // 追補 §16.2: 全マス空白行は <tr> を立てない。
                                else -> {
                                    flushLogical()
                                    openLogical = allCells.toMutableList()
                                    openContinued = livingNext(openContinued, allCells) && rowHeadered
                                }
                            }
                        }
                        segmentHeader != null -> {
                            // RFC 0013 追補 §15: 同一グリッドまたは列細分化 refinement の pending ヘッダ行を取り込む。
                            // 細分列番号ヘッダ（digitHeaderLike）は grid 左辺が本文行と共有でなくても表頭として Adoptする。
                            // それ以外（交差・無関係グリッド）は段落退避し、保存則 §2.2 を維持する。
                            val pendingLefts = if (pending.isNotEmpty()) lefts(pending.last()).toSet() else emptySet()
                            val contentLefts = lefts(content).toSet()
                            if (pending.isNotEmpty() &&
                                (pendingLefts == contentLefts ||
                                    contentLefts.containsAll(pendingLefts) ||
                                    pendingLefts.containsAll(contentLefts) ||
                                    digitHeaderLike(pending.last()))
                            ) {
                                flushPara()
                                tableRows = pending.toMutableList()
                                pending.clear()
                            } else {
                                abandonPendingToParagraph()
                                flushPara()
                                tableRows = mutableListOf()
                            }
                            openLogical = allCells.toMutableList()
                            openContinued = livingNext(openContinued, allCells) && segmentHeader != null
                        }
                        pending.isNotEmpty() && lefts(pending.last()) == lefts(content) -> {
                            // 安定グリッド 2 行目: pending を引きずり込んで開く。
                            flushPara()
                            tableRows = pending.toMutableList()
                            pending.clear()
                            // RFC 0013 追補 §16v2: 開き行が WRAP-open（band 先頭）なら、
                            // tableRows に取り込んだ pending の band 宣言行を band へ移し、
                            // 素の `<tr>` として残さない。列番号等の非 open 行は表頭として残す。
                            if (rowWrapOpen(allCells)) {
                                val carried = tableRows!!.filter { rowWrapOpen(it) }
                                tableRows!!.removeAll(carried)
                                bandBuffer = (carried + listOf(allCells.toList())).toMutableList()
                            } else {
                                openLogical = allCells.toMutableList()
                                openContinued = livingNext(openContinued, allCells) && segmentHeader != null
                            }
                        }
                        else -> pending.add(content)
                    }
                }
            }
            segmentCells.clear()
            current = null
            segmentHeader = null
        }

        // WALL なしのヘッダ連続でセグメントが確定されない場合の防御的クローズ。
        fun closeSegmentIfNeeded() {
            if (current != null || segmentCells.isNotEmpty()) {
                finalizeSegment()
            }
        }

        for (event in events) {
            when (event) {
                is FlowEvent.ParagraphHeader -> {
                    closeSegmentIfNeeded()
                    if (tableRows != null) abandonPendingToParagraph()
                    flushPara()
                }

                is FlowEvent.RowHeader -> {
                    if (current != null || segmentCells.isNotEmpty()) {
                        finalizeSegment()
                    }
                    segmentHeader = event
                    flushPara()
                }

                is FlowEvent.SpanDeclaration -> {
                    val currentCell = current
                    if (currentCell == null ||
                        (currentCell.left != event.left || currentCell.right != event.right)
                    ) {
                        // 同じ (l,r) は同一セル継続（coalesce・no-op）。
                        // 違えば確定し新規開始（ヘッダなし span は新セグメント開始）。
                        if (currentCell != null) {
                            segmentCells.add(normalizeCell(currentCell))
                        }
                        current = CellDraft(
                            event.left,
                            event.right,
                            mutableListOf(),
                            flags = event.flags,
                            flags2 = event.flags2,
                        )
                    }
                }

                is FlowEvent.Text -> {
                    (current?.parts ?: paraParts).add(FlowPart.Text(event.text))
                }

                is FlowEvent.Wrap -> {
                    val parts = current?.parts
                    if (parts != null) {
                        parts.add(FlowPart.Break)
                    } else if (paraParts.isNotEmpty()) {
                        paraParts.add(FlowPart.Break)
                    }
                }

                is FlowEvent.RowAdvance -> {
                    finalizeSegment()
                    flushPara()
                }

                is FlowEvent.SectionTransition -> {
                    finalizeSegment()
                    commitTable()
                    abandonPendingToParagraph()
                    flushPara()
                }

                is FlowEvent.WallRule -> Unit
            }
        }
        // EOS flush（ストリーム終、RFC 0013 §3.7）。
        finalizeSegment()
        commitTable()
        abandonPendingToParagraph()
        flushPara()
        return blocks
    }

    /** RFC 0013 追補 §14 R4: 下方アンカーへ直下の同一スパン断片行を吸収する。 */
    private fun absorbAnchorFragments(input: List<List<CellDraft>>): List<MutableList<CellDraft>> {
        val rows = input.map { it.toMutableList() }
        val out = mutableListOf<MutableList<CellDraft>>()
        var r = 0
        while (r < rows.size) {
            val anchors = rows[r].filter { it.flags2 == 0x0002 && !it.isBlank() }
            if (anchors.isEmpty() || r + 1 >= rows.size) {
                out.add(rows[r++])
                continue
            }
            val next = rows[r + 1]
            val fragments = next.filter { !it.isBlank() }
            val anchorSpans = anchors.map { it.left to it.right }.toSet()
            val fragmentSpans = fragments.map { it.left to it.right }.distinct()
            val absorbable = fragments.isNotEmpty() && fragmentSpans.size == 1 && fragments.all {
                (it.left to it.right) in anchorSpans
            }
            if (!absorbable) {
                out.add(rows[r++])
                continue
            }
            for (fragment in fragments) {
                val anchor = anchors.first { it.left == fragment.left && it.right == fragment.right }
                while (fragment.parts.firstOrNull() is FlowPart.Break) fragment.parts.removeAt(0)
                while (fragment.parts.lastOrNull() is FlowPart.Break) fragment.parts.removeAt(fragment.parts.lastIndex)
                if (fragment.parts.isNotEmpty()) {
                    anchor.parts.add(FlowPart.LineBreak)
                    anchor.parts.addAll(fragment.parts)
                }
            }
            out.add(rows[r])
            r += 2
        }
        return out
    }

    /** RFC 0013 追補 §14 R3: 空白の狭幅罫断片を他行の真包含スパンへ折り込む。 */
    private fun foldRuleFragments(input: List<List<CellDraft>>): List<MutableList<CellDraft>> {
        val declarations = input.flatten()
        return input.map { row ->
            val result = mutableListOf<CellDraft>()
            var i = 0
            while (i < row.size) {
                val cell = row[i]
                val cover = if (cell.isBlank() && cell.right - cell.left <= 8) {
                    declarations.filter {
                        it.left <= cell.left && cell.right <= it.right &&
                            (it.left != cell.left || it.right != cell.right) &&
                            it.right - it.left > cell.right - cell.left
                    }.minByOrNull { it.right - it.left }
                } else null
                if (cover == null) {
                    result.add(cell)
                    i++
                    continue
                }
                val run = mutableListOf<CellDraft>()
                while (i < row.size) {
                    val part = row[i]
                    if (!part.isBlank() || part.right - part.left > 8 ||
                        part.left < cover.left || part.right > cover.right
                    ) break
                    run.add(part)
                    i++
                }
                result.add(CellDraft(cover.left, cover.right, mutableListOf(), flags = run.first().flags))
            }
            result
        }
    }

    /** RFC 0013 追補 §14 R5: 空白 WRAP ブロック列をアンカーセルへ折り込む。 */
    private fun foldBlockColumns(
        input: List<List<CellDraft>>,
        spansOut: MutableMap<Pair<Int, Int>, Int>,
    ): List<MutableList<CellDraft>> {
        val rows = input.map { it.toMutableList() }
        val declarations = input.flatten()
        var r = 0
        while (r < rows.size) {
            val starts = rows[r].filter {
                it.isBlank() && it.endedWithBreak &&
                    it.right - it.left > BOUNDARY_STRIP_WIDTH &&
                    it.right - it.left <= BLOCK_COL_MAX_WIDTH
            }
            for (start in starts) {
                val startIndex = rows[r].indexOf(start)
                if (startIndex < 0) continue
                var e = r + 1
                var textSeen = false
                val removals = mutableListOf<Pair<Int, CellDraft>>()
                val accumulated = mutableListOf<FlowPart>()
                while (e < rows.size) {
                    val cont = rows[e].firstOrNull { it.left == start.left && it.right == start.right } ?: break
                    if (cont.isBlank() && cont.endedWithBreak) break
                    if (cont.isBlank()) {
                        removals.add(e to cont)
                        e++
                        continue
                    }
                    if (!cont.endedWithBreak) break
                    val parts = cont.parts.toMutableList()
                    while (parts.firstOrNull() is FlowPart.Break) parts.removeAt(0)
                    while (parts.lastOrNull() is FlowPart.Break) parts.removeAt(parts.lastIndex)
                    if (parts.isNotEmpty()) {
                        if (textSeen) accumulated.add(FlowPart.LineBreak)
                        accumulated.addAll(parts)
                        textSeen = true
                        removals.add(e to cont)
                    }
                    e++
                }
                val length = e - r
                if (textSeen && length >= 2) {
                    start.parts.clear()
                    start.parts.addAll(accumulated)
                    spansOut[r to startIndex] = length
                    for ((rowIndex, cell) in removals) rows[rowIndex].remove(cell)
                    // 空白広幅セルを同じブロック窓で colspan×rowspan 化。
                    val candidates = rows[r].filter { it !== start && it.isBlank() && it.right - it.left > BOUNDARY_STRIP_WIDTH }
                    for (candidate in candidates) {
                        val hasInternalBoundary = declarations.any {
                            candidate.left < it.left && it.left < candidate.right
                        }
                        if (!hasInternalBoundary) continue
                        val matching = (r + 1 until min(e, rows.size)).map { rowIndex ->
                            rows[rowIndex].firstOrNull {
                                it.left == candidate.left && it.right == candidate.right && it.isBlank()
                            }
                        }
                        if (matching.size == length - 1 && matching.all { it != null }) {
                            spansOut[r to rows[r].indexOf(candidate)] = length
                            for (rowIndex in r + 1 until min(e, rows.size)) {
                                matching[rowIndex - r - 1]?.let { rows[rowIndex].remove(it) }
                            }
                        }
                    }
                }
            }
            r++
        }
        return rows
    }

    /**
     * RFC 0013 §3.6 正規化: グリッド左辺座標の和集合で列槽を決め、
     * 各行の欠落槽は空 FlowCell で補間（padding）、跨ぎは colspan 近似。
     */
    /** 追補 §16v2 継ぎ手: band 内縦流れの同一レーン連結。 */
    private fun joinVerticalRun(dst: MutableList<FlowPart>, src: List<FlowPart>) {
        var parts = src
        while (parts.firstOrNull() is FlowPart.Break) parts = parts.drop(1)
        if (parts.isEmpty()) {
            if (src.any { it is FlowPart.Break } && dst.isNotEmpty() && dst.last() !is FlowPart.Break) dst.add(FlowPart.Break)
            return
        }
        val t = parts.filterIsInstance<FlowPart.Text>().joinToString("") { it.text }
        if (t.isBlank()) {
            if (parts.any { it is FlowPart.Break } && dst.isNotEmpty() && dst.last() !is FlowPart.Break) dst.add(FlowPart.Break)
            return
        }
        when {
            t.all { it.isDigit() } -> { if (dst.isNotEmpty()) dst.add(FlowPart.Text(" ")); dst.add(FlowPart.Text(t)) }
            t.all { it.code in 0x20..0x7e } -> {
                if (dst.isNotEmpty() && dst.none { it is FlowPart.Text && it.text.endsWith(" ") }) dst.add(FlowPart.Text(" "))
                dst.add(FlowPart.Text(t))
                // 半角英数 run の直後に CJK が直結して固着するのを防ぐ（gold: "or 美術"）。
                dst.add(FlowPart.Text(" "))
            }
            t.length == 1 -> dst.add(FlowPart.Text(t))
            else -> { if (dst.isNotEmpty()) dst.add(FlowPart.LineBreak); dst.add(FlowPart.Text(t)) }
        }
    }

    /**
     * 追補 §16v2: band 物理行列を可視行列へ再構成する。
     * 同一 span の欠落後の再開、または閉じた親 span 内の新 span 出現を可視境界とする。
     * 返り値の 2 番目は tall マス span 強制指定（(可視行index, 行内cellindex)→rowspan）で、
     * buildTable の欠落槽走査が行外（列番号 footer 等）へ過剰伸長するのを防ぐ。
     */
    private fun reconstructBand(band: List<List<CellDraft>>): Pair<List<List<CellDraft>>, Map<Pair<Int, Int>, Int>> {
        data class Lane(var closeRow: Int = -1)
        class BandUnit(val left: Int, val right: Int, val startRow: Int, var lastRow: Int, val parts: MutableList<FlowPart>)
        val lanes = mutableMapOf<Pair<Int, Int>, Lane>()
        val units = mutableListOf<BandUnit>()
        val vOfRow = IntArray(band.size)
        var v = 0
        for ((i, row) in band.withIndex()) {
            var boundary = false
            for (c in row) {
                val t = c.parts.filterIsInstance<FlowPart.Text>().joinToString("") { it.text }
                if (t.isBlank()) continue
                val s = c.left to c.right
                val lane = lanes[s]
                if (lane != null && lane.closeRow in 0 until i &&
                    (lane.closeRow + 1 until i).any { r -> band[r].none { it.left == s.first && it.right == s.second } }
                ) { boundary = true; break }
                if (lane == null && lanes.entries.any { (k, l) ->
                        l.closeRow in 0 until i && k.first <= c.left && c.right <= k.second && k != s &&
                            band.subList(l.closeRow + 1, i + 1).none { r -> r.any { it.left == k.first && it.right == k.second } }
                    }
                ) { boundary = true; break }
            }
            if (boundary) v++
            vOfRow[i] = v
            for (c in row) {
                val s = c.left to c.right
                // 直近の連続欠落行数: 0 なら同一レーンの継続（閉塞直後の即再開はセル内折返し）。
                var absentRun = 0
                var k = i - 1
                while (k >= 0 && band[k].none { it.left == c.left && it.right == c.right }) {
                    absentRun++
                    k--
                }
                val openUnit = if (absentRun == 0) {
                    units.lastOrNull { it.left == c.left && it.right == c.right }
                } else {
                    null
                }
                val target = openUnit ?: BandUnit(c.left, c.right, i, i, mutableListOf()).also { units.add(it) }
                target.lastRow = i
                joinVerticalRun(target.parts, c.parts)
                val state = lanes.getOrPut(s) { Lane() }
                state.closeRow = if (c.endedWithBreak) -1 else i
            }
        }
        val rows = Array(v + 1) { mutableListOf<CellDraft>() }
        val tallAtRow = Array(v + 1) { mutableMapOf<Int, Int>() }
        for (u in units) {
            val ps = u.parts.toMutableList()
            while (ps.firstOrNull() is FlowPart.Break) ps.removeAt(0)
            while (ps.lastOrNull() is FlowPart.Break) ps.removeAt(ps.lastIndex)
            val v0 = vOfRow[u.startRow]
            val v1 = vOfRow[u.lastRow]
            rows[v0].add(CellDraft(u.left, u.right, ps))
            if (v1 > v0) tallAtRow[v0][u.left] = v1 - v0 + 1
        }
        val out = rows.map { it.sortedBy { cell -> cell.left } }
        val forced = mutableMapOf<Pair<Int, Int>, Int>()
        out.forEachIndexed { vi, row ->
            row.forEachIndexed { ci, cell ->
                tallAtRow[vi][cell.left]?.let { forced[vi to ci] = it }
            }
        }
        return out to forced
    }

    private fun buildTable(
        inputRows: List<List<CellDraft>>,
        preSpans: Map<Pair<Int, Int>, Int> = emptyMap(),
    ): FlowBlock.Table {
        val folded = foldRuleFragments(absorbAnchorFragments(inputRows))
        val blockSpans = mutableMapOf<Pair<Int, Int>, Int>()
        var rows: List<List<CellDraft>> = foldBlockColumns(folded, blockSpans)

        // 追補 §3.6: 罫表外側残り物幻影空列の除去（時割選択様式観測）。幻影のbyte級 signature は
        // 「細幅（≤8）の端列が全行で宣言され続け、全マス空白」。意図的な空欄マス
        // （記入欄・斜め罫・§3.6 補間契約の空槽）は細幅でないこと／全行宣言でないことで
        // 見逃す。除去した先頭マス数へ preSpans / blockSpans の cellIndex をシフトし、
        // 保存則: 空白列のため本文欠落なし。
        val trimShifts = HashMap<Int, Int>()
        while (rows.isNotEmpty()) {
            var coords = rows.flatten().map { it.left }.distinct().sorted()
            if (coords.size < 2) break
            var changed = false
            val head = coords.first()
            if (rows.all { row -> row.any { it.left == head } } &&
                rows.flatten().filter { it.left == head }.all { it.isBlank() && it.right - head <= 8 }
            ) {
                rows = rows.mapIndexed { r, row ->
                    val drop = row.count { it.left == head }
                    if (drop > 0) trimShifts[r] = (trimShifts[r] ?: 0) + drop
                    row.filter { it.left != head }
                }
                changed = true
                coords = coords.drop(1)
            }
            if (coords.size >= 2) {
                val tail = coords.last()
                if (rows.all { row -> row.any { it.left == tail } } &&
                    rows.flatten().filter { it.left == tail }.all { it.isBlank() && it.right - tail <= 8 }
                ) {
                    rows = rows.map { row -> row.filter { it.left != tail } }
                    changed = true
                }
            }
            if (!changed) break
        }
        if (trimShifts.isNotEmpty()) {
            fun remap(map: MutableMap<Pair<Int, Int>, Int>) {
                val snapshot = map.toMap()
                map.clear()
                for ((k, s) in snapshot) {
                    val c = k.second - (trimShifts[k.first] ?: 0)
                    if (c >= 0) map[k.first to c] = s
                }
            }
            remap(blockSpans)
        }
        val grid = rows.flatten().map { it.left }.distinct().sorted()
        fun coveredSlots(cell: CellDraft): Set<Int> = grid.indices.filterTo(mutableSetOf()) {
            cell.left <= grid[it] && grid[it] <= cell.right
        }
        val rowCells = rows.map { it.sortedBy { cell -> cell.left } }
        val rowspans = mutableMapOf<Pair<Int, Int>, Int>()
        val continuedSlots = rows.indices.map { mutableSetOf<Int>() }
        for (r in rows.indices) {
            for ((c, cell) in rowCells[r].withIndex()) {
                val blockSpan = blockSpans[r to c] ?: preSpans[r to (c + (trimShifts[r] ?: 0))]
                if (blockSpan != null) {
                    rowspans[r to c] = blockSpan
                    for (next in r + 1 until min(r + blockSpan, rows.size)) {
                        continuedSlots[next].addAll(coveredSlots(cell))
                    }
                    continue
                }
                if (cell.parts.filterIsInstance<FlowPart.Text>().joinToString("").isBlank()) continue
                val slots = coveredSlots(cell)
                val slotCoordinates = slots.map { grid[it] }.toSet()
                var end = r + 1
                while (end < rows.size) {
                    val covered = rowCells[end].flatMap { coveredSlots(it) }.toSet().intersect(slots)
                    val sameSlotStarts = rowCells[end].any { it.left in slotCoordinates }
                    if (covered.isNotEmpty() || sameSlotStarts) break
                    end++
                }
                if (end > r + 1) {
                    rowspans[r to c] = end - r
                    for (next in r + 1 until end) continuedSlots[next].addAll(slots)
                }
            }
        }
        val flowRows = rows.indices.map { rowIndex ->
            val cells = rowCells[rowIndex]
            val out = mutableListOf<FlowCell>()
            var j = 0
            for ((cellIndex, cell) in cells.withIndex()) {
                // 欠落槽ごとに空 FlowCell を補間。
                if (j < grid.size && grid[j] < cell.left) {
                    while (j < grid.size && grid[j] < cell.left) {
                        if (j !in continuedSlots[rowIndex]) {
                            out.add(FlowCell(grid[j], grid[j] + BOUNDARY_STRIP_WIDTH, 1, emptyList()))
                        }
                        j++
                    }
                }
                var colspan = if (cells.size == 1 && cell.endedWithBreak) 1 else max(1, grid.count { it in cell.left..cell.right })
                colspan = min(colspan, grid.size - j)
                out.add(FlowCell(cell.left, cell.right, colspan, cell.parts.toList(), rowspans[rowIndex to cellIndex] ?: 1))
                while (j < grid.size && grid[j] <= cell.right) j++
            }
            // 行末の残り槽も槽ごとに補間。
            if (j < grid.size) {
                while (j < grid.size) {
                    if (j !in continuedSlots[rowIndex]) {
                        out.add(FlowCell(grid[j], grid[j] + BOUNDARY_STRIP_WIDTH, 1, emptyList()))
                    }
                    j++
                }
            }
            FlowRow(out)
        }
        return FlowBlock.Table(flowRows)
    }
}

/**
 * RFC 0013 §5 出力射印: [FlowBlock] 列を XHTML SAX イベントへ射影する。
 *
 * - [FlowBlock.Paragraph] → `<p>`（[FlowPart.Text]→characters、[FlowPart.Break]→`<br/>`）
 * - [FlowBlock.Table] → `<table><tbody>` + 行ごと `<tr>` + セルごと `<td>`。
 *   `colspan > 1` / `rowspan > 1` のとき属性を付与。
 *   [FlowCell.parts] が空なら中身なしの `<td></td>`。
 *
 * Tika 4 pipes invariant: startElement した要素は必ず閉じる（emit 順序に矛盾を持たせない）。
 */
internal object FlowSaxEmitter {

    fun emit(blocks: List<FlowBlock>, xhtml: XHTMLContentHandler) {
        for (block in blocks) {
            when (block) {
                is FlowBlock.Paragraph -> emitParagraph(block, xhtml)
                is FlowBlock.Table -> emitTable(block, xhtml)
            }
        }
    }

    private fun emitParagraph(block: FlowBlock.Paragraph, xhtml: XHTMLContentHandler) {
        xhtml.startElement("p")
        emitParts(block.parts, xhtml, inCell = false)
        xhtml.endElement("p")
    }

    private fun emitTable(block: FlowBlock.Table, xhtml: XHTMLContentHandler) {
        xhtml.startElement("table")
        xhtml.startElement("tbody")
        for (row in block.rows) {
            xhtml.startElement("tr")
            for (cell in row.cells) {
                val attrs = AttributesImpl()
                if (cell.rowspan > 1) attrs.addAttribute("", "rowspan", "rowspan", "CDATA", cell.rowspan.toString())
                if (cell.colspan > 1) attrs.addAttribute("", "colspan", "colspan", "CDATA", cell.colspan.toString())
                if (attrs.length == 0) xhtml.startElement("td") else xhtml.startElement("td", attrs)
                if (cell.parts.isEmpty()) {
                    // 空セル: 中身なし <td></td>。Tika 4 の ToXMLContentHandler は自己閉じを
                    // <td/>（半角スペース入り・ENDLINE で改行付）と出すため、空 characters
                    // （Tika 725 の <title></title> 優先と同一技）で開閉タグを明示する。
                    xhtml.characters(charArrayOf(), 0, 0)
                } else {
                    emitParts(cell.parts, xhtml, inCell = true)
                }
                xhtml.endElement("td")
            }
            xhtml.endElement("tr")
        }
        xhtml.endElement("tbody")
        xhtml.endElement("table")
    }

    private fun emitParts(parts: List<FlowPart>, xhtml: XHTMLContentHandler, inCell: Boolean) {
        for (part in parts) {
            when (part) {
                is FlowPart.Text -> xhtml.characters(part.text)
                is FlowPart.LineBreak -> {
                    // 縦継続セルの論理行内テキスト境界（RFC 0013 追補 §13.2）。
                    xhtml.startElement("br")
                    xhtml.endElement("br")
                }
                is FlowPart.Break -> {
                    if (inCell) {
                        xhtml.characters(" ")
                        continue
                    }
                    // XHTMLContentHandler には emptyElement がなく、
                    // element(name, "") は空値を emit しないため start/end ペアで代用する。
                    xhtml.startElement("br")
                    xhtml.endElement("br")
                }
            }
        }
    }
}
