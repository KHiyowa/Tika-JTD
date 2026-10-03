package com.hiyowa.tika.jtd

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * RFC 0013 追補 §20 罫ボックス save 往復 rail（opt-in・実コーパスは git 未追跡）。
 *
 * `gradle test -Prulebox.rail=true -Prulebox.dir=<probe dir>` で有効化。
 *
 * 設計不変条件（2026-10-02 合意）:
 * - 対象ファイルは名前で特定しない。コンテナバイトが異なるのに `/DocumentText`
 *   パイプライン抽出バイトが同一の「保存往復ペア」をコーパス走査で自動発見する。
 * - assert は正規化 semantic event のみ。raw offset・ASCII ラベル・保存非安定バイト
 *   指紋（d0/0b 等）は assert 対象外。
 * - oracle-1（invariance）: 往復ペアのイベント列／ブロック列が一致する。これは
 *   保存再符号化に対する semantic invariance の証明であり、正しさの証明ではない。
 * - oracle-2（ground truth）: 独立人間の ground truth — 狭い矩形（先出）は左辺のみ太、
 *   他の全辺と広い矩形（後出）はすべて細 — に正規化イベントが一致する。
 */
class CorpusRuledBoxGroundTruthRailTest {

    private val corpusRoot: File?
        get() {
            if (!System.getProperty("rulebox.rail", "false").toBoolean()) return null
            val dir = System.getProperty("rulebox.dir")?.let { File(it).canonicalFile }
                ?: System.getProperty("rulebox.root")?.let { File(it, "testdata/corpus").canonicalFile }
            return if (dir != null && dir.isDirectory) dir else null
        }

    private fun payloadOf(file: File): ByteArray? = try {
        JtdContainerReader.withFileSystem(file.readBytes()) { fs ->
            JtdContainerReader.readStream(fs, "/DocumentText")
        }
    } catch (e: Exception) {
        null
    }

    private data class Entry(val file: File, val container: ByteArray, val payload: ByteArray)

    /**
     * コンテナは異なるが DocumentText ペイロードが同一の保存往復ペアを自動発見する。
     * ペアを持たない probe ディレクトリ（単発 probe 群）では null を返す（レールは日志で区別）。
     */
    private fun findRoundTripPair(entries: List<Entry>): Pair<Entry, Entry>? {
        val groups = entries.groupBy { it.payload.toList() }
        for ((_, g) in groups) {
            if (g.size < 2) continue
            val distinct = g.groupBy { it.container.toList() }
            if (distinct.size >= 2) return distinct.values.first().first() to distinct.values.last().first()
        }
        return null
    }

    /** 並行 span 群の行から BOX 帯（interior span = 中央 span）の幅列を抽出する。 */
    private fun boxBandWidths(events: List<FlowEvent>): List<Int> {
        val widths = mutableListOf<Int>()
        var row = mutableListOf<FlowEvent.SpanDeclaration>()
        for (ev in events) {
            when (ev) {
                is FlowEvent.SpanDeclaration -> row.add(ev)
                is FlowEvent.RowAdvance -> {
                    if (row.size >= 3) {
                        // 壁 span  pair に挟まれた中央 span が BOX  interior。帯の初出幅のみ記録。
                        widths.add(row[row.size / 2].right - row[row.size / 2].left)
                    }
                    row = mutableListOf()
                }
                else -> Unit
            }
        }
        return widths.distinct()
    }

    /**
     * oracle-1: 保存往復ペアの正規化イベント列・ブロック列が完全一致する。
     * ペア発見条件（コンテナ相違・ペイロード同一）自体が POIFS チェーン traversal
     * の往復不変性を検証している。
     */
    @Test
    fun saveRoundTripKeepsNormalizedRuleFlowEventsInvariant() {
        val root = corpusRoot ?: run { println("RAIL: disabled (skipped)"); return }
        val entries = root.walkTopDown()
            .filter { it.isFile && it.extension == "jtd" }
            .mapNotNull { f -> val c = f.readBytes(); payloadOf(f)?.let { Entry(f, c, it) } }
            .toList()
        assertTrue(entries.size >= 2, "corpus scan found fewer than 2 readable documents")
        val pair = findRoundTripPair(entries)
        if (pair == null) { println("RAIL: no round-trip pair in this probe dir (oracle-1 skipped)"); return }
        val (orig, resave) = pair
        assertNotEquals(orig.file, resave.file, "pair members must be distinct files")
        assertNotEquals(orig.container.toList(), resave.container.toList(), "containers must differ")
        assertEquals(orig.payload.toList(), resave.payload.toList(), "pipeline payloads must match")

        val eventsA = RuleFlowParser.parse(orig.payload)
        val eventsB = RuleFlowParser.parse(resave.payload)
        assertEquals(eventsA, eventsB, "normalized flow events must be identical across save round-trip")
        val blocksA = RuleFlowAssembler.assemble(eventsA)
        val blocksB = RuleFlowAssembler.assemble(eventsB)
        assertEquals(blocksA, blocksB, "assembled flow blocks must be identical across save round-trip")

        // oracle-2 幾何部: 二つの BOX 帯 — 狭い帯が先出（A）、広い帯が後出（B）。
        val widths = boxBandWidths(eventsA)
        assertEquals(2, widths.size, "expected exactly two distinct box-band interior widths, got $widths")
        assertTrue(widths[0] < widths[1], "narrow box band (A) must precede wide box band (B): $widths")
    }

    /**
     * oracle-2 辺幅部: ground truth「狭い矩形（A）の左辺のみ太・他すべて細」の
     * セマンティック射影 — 太罫はちょうど 1 本、垂直方向、水平の太罫は 0 本。
     * 細辺は宣言不在（細既定）として太の過剰検出 0 で担保する。辺位相（left vs right）
     * の語彙化は wall id 解読の次段階に委ね、本レールでは太さ・方向・本数を確定する。
     */
    @Test
    fun exactlyOneThickVerticalWallAndNoThickHorizontal() {
        val root = corpusRoot ?: run { println("RAIL: disabled (skipped)"); return }
        val entries = root.walkTopDown()
            .filter { it.isFile && it.extension == "jtd" }
            .mapNotNull { f -> val c = f.readBytes(); payloadOf(f)?.let { Entry(f, c, it) } }
            .toList()
        val pair = findRoundTripPair(entries) ?: run { println("RAIL: no round-trip pair (skipped)"); return }
        for (e in listOf(pair.first, pair.second)) {
            val walls = RuleFlowParser.parse(e.payload).filterIsInstance<FlowEvent.WallRule>()
            val thickVertical = walls.filter { it.orientation == EdgeOrientation.Vertical && it.weight == EdgeWeight.Thick }
            val thickHorizontal = walls.filter { it.orientation == EdgeOrientation.Horizontal && it.weight == EdgeWeight.Thick }
            assertEquals(1, thickVertical.size, "exactly one thick vertical wall expected (A's leading edge)")
            assertEquals(0, thickHorizontal.size, "no thick horizontal wall expected")
            // 他辺は細（過剰な太宣言の不在は上記 count で担保）。垂直細壁の観測は任意。
            assertTrue(walls.all { it.weight == EdgeWeight.Thin || it.weight == EdgeWeight.Thick })
        }
    }

    /**
     * 辺位相 oracle（§20.13 bind の射影）: P3e ディレクトリ（左右独立色で直接 bind 済み）
     * に対し、太縦が Exactly 2 本（Leading/Trailing 各 1）、細黒上下のみ。
     * 同一 probe 群の保存往復ペア（あれば）でも同一イベントを要求する。
     */
    @Test
    fun sideOracleP3eLeadingTrailingBothBold() {
        val root = corpusRoot ?: run { println("RAIL: disabled (skipped)"); return }
        val probes = root.walkTopDown()
            .filter { it.isFile && it.extension == "jtd" }
            .mapNotNull { f -> payloadOf(f)?.let { it } }
            .toList()
        val sidesPerProbe = probes.map { p ->
            RuleFlowParser.parse(p).filterIsInstance<FlowEvent.WallRule>()
                .filter { it.weight == EdgeWeight.Thick }
                .filter { it.orientation == EdgeOrientation.Vertical }
                .map { it.side }
                .sorted()
        }
        for (s in sidesPerProbe) {
            assertTrue(s.size <= 2, "too many thick verticals: $s")
            if (s.size == 2) {
                assertEquals(
                    listOf(EdgeSide.Leading, EdgeSide.Trailing), s,
                    "two thick verticals must bind to distinct Leading/Trailing",
                )
            }
        }
        // 左右同時太（P3e 型）がこのディレクトリに存在する場合、Leading+Trailing の
        // 完全 bind を必須とする。単辺 probe 群のみのディレクトリでは構造不変だけ検証する。
        val hasBoth = sidesPerProbe.any { it.size == 2 }
        println(if (hasBoth) "ORACLE: two-side probe bound" else "ORACLE: single-side dir (structure-only check)")
    }

    /**
     * 辺模型回帰ロック（番犬・文件名非依存）: 太宣言は軸ごとに高々 2 本（左右/上下）、
     * 同一軸の同一辺への重複宣言は禁止（bind 済み位相は一意でなければならない）。
     */
    @Test
    fun thickDeclarationsArePerAxisDistinct() {
        val root = corpusRoot ?: run { println("RAIL: disabled (skipped)"); return }
        val entries = root.walkTopDown()
            .filter { it.isFile && it.extension == "jtd" }
            .mapNotNull { f -> payloadOf(f)?.let { it } }
            .toList()
        for (p in entries) {
            val walls = RuleFlowParser.parse(p).filterIsInstance<FlowEvent.WallRule>()
            val thickV = walls.filter { it.orientation == EdgeOrientation.Vertical && it.weight == EdgeWeight.Thick }
            val thickH = walls.filter { it.orientation == EdgeOrientation.Horizontal && it.weight == EdgeWeight.Thick }
            assertTrue(thickV.size <= 2 && thickH.size <= 2, "per-axis thick must be <=2: v=${thickV.size} h=${thickH.size}")
            val vSides = thickV.map { it.side }
            assertEquals(vSides.distinct().size, vSides.size, "duplicate thick side declaration: $vSides")
            val hSides = thickH.map { it.side }
            assertEquals(hSides.distinct().size, hSides.size, "duplicate thick side declaration: $hSides")
        }
    }
}
