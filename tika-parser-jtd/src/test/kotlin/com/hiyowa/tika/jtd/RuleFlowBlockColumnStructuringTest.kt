package com.hiyowa.tika.jtd

import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.TABLE_RIGHT
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.WALL
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.WRAP
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.cell
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.cfb
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.record
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.rowHeader
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.stream
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.text
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.transition
import java.io.ByteArrayInputStream
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.ToXMLContentHandler
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC 0013 追補 §14 録図グリッド構造（孤立罫断片・継続錨・ブロック列）の契約テスト。
 *
 * 実コーパス观測（記入様式＋統計表ハイブリッド文書）で確定した 5 契約:
 * - R1: `0x0030`/`0x0010` レコード本体の語が `0x001d`（ INLINE_TEXT_START と同値）でも、
 *   直後が `0x001e` でないなら正当座標語として承認する（誤拒否による表分断・
 *   len 語 `0x0029` の本文 `"）"` 化を防止。RFC 0009 §誤検出の連番語列ガードは維持）。
 * - R2: 縦継続統合（追補 §13.2）の収縮 — 後続行が先行行の閉じた（非空白・WRAP なし）
 *   同一スパンに新テキストを持つなら別論理行（§3.4 確定的行境界の優先）。
 * - R3: 空白の罫断片スパン（幅 ≤8・他行のより広い宣言スパンに真包含）は包含スパンへ
 *   折込合体し、包含側のグリッド左辺を汚染しない（打ち消し線・孤立罫の両側断片）。
 * - R4: 語6=0x0002（下方継続アンカー）セルの直下 WALL 行同一スパン断片（語6=0x0000）は
 *   アンカーセルへ `<br/>` 継ぎで吸収し、断片行を独立 tr にしない。
 * - R5: 先頭宣言スパンが空白+WRAP の行はブロック先頭。同一スパンのブロック列を
 *   ブロック先頭セルへ rowspan 合体（断片テキストは `<br/>` 継ぎ）。
 *   ブロック内空白の広幅セルが他行の内部境界スパンを真包含なら colspan×rowspan 合体。
 *
 * 本文文言は『銀河鉄道の夜』（青空文庫・パブリックドメイン）から引用（AGENTS.md 準拠）。
 */
class RuleFlowBlockColumnStructuringTest {

    private fun parseXhtml(data: ByteArray): String {
        val handler = ToXMLContentHandler()
        TikaInputStream.get(ByteArrayInputStream(data)).use { s ->
            JtdParser().parse(s, handler, Metadata(), ParseContext())
        }
        return compact(handler.toString())
    }

    private fun compact(xml: String): String =
        xml
            .replace(Regex("<br\\s*/>\\s*"), "<br/>")
            .replace(Regex(">\\s+<"), "><")
            .trim()

    private fun textOnly(xml: String): String =
        xml.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), "")

    private fun trCount(xml: String) = Regex("<tr\\b").findAll(xml).count()
    private fun tableCount(xml: String) = Regex("<table\\b").findAll(xml).count()

    // ------------------------------------------------------------------
    // R1: 行ヘッダ内の 0x001d 同値語を承認（誤拒否防止）
    // ------------------------------------------------------------------

    /** 行ヘッダの w5 が 0x001d（インライン開始と同値）でもレコードを承認する。 */
    @Test
    fun acceptsRowHeaderSpanRegionWordEqualToInlineStart() {
        // 実文書の実測形: len=0x0029（41='）' と同値）で w5=0x001d の行間区切りヘッダ
        // （§3.1 算術非準拠・0x001d の直後は 0x0094）。現行実装は 0x001d を理由に
        // 誤拒否し、拒否後の len 語 0x0029 が本文 ')' として漏れ表が分断する。
        val separatorHeader = record(
            0x0010,
            listOf(
                0x0000, 0x008f, 0x001d, 0x0094, 0x0000, 0x0002,
                0x0017, 0x0000, 0x0014, 0x0012, 0x0014, 0x0001,
                0x0017, 0x0000, 0x0014, 0x001a, 0x0014, 0x000b,
                0x0017, 0x0000, 0x0014, 0x001a, 0x0014, 0x000b,
                0x0017, 0x0000, 0x0014, 0x001c, 0x0014, 0x0007,
                0x0014, 0x0001, 0x0013, 0x0000, 0x0000, 0x0001,
            ),
        )
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった"))
        words.add(WALL)
        words.addAll(separatorHeader)
        words.addAll(cell(0, 2))
        words.addAll(cell(146, 148))
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("天上"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("白い"))
        words.add(WALL)
        words.addAll(transition())

        val data = stream(words)
        val events = RuleFlowParser.parse(data)
        assertTrue(
            events.any { it is FlowEvent.RowHeader && it.w4 == 0x008f },
            "w5=0x001d の行ヘッダが誤拒否された: $events",
        )
        assertTrue(
            events.none { it is FlowEvent.Text && it.text.contains(')') },
            "拒否レコードの len 語 が本文へ漏れた: $events",
        )

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to data)))
        assertEquals(1, tableCount(xml), "行ヘッダ誤拒否による表分断: $xml")
        assertEquals(2, trCount(xml), "2 物理行は保たれるべき: $xml")
        assertEquals("銀河けぶった天上白い", textOnly(xml), "テキスト全量保存則")
    }

    // ------------------------------------------------------------------
    // R2: 閉じたセル衝突で行統合を収縮（§13.2 の限定）
    // ------------------------------------------------------------------

    /** 後続行が先行行の閉じたテキストセルへ別のテキストを開始するなら別 `<tr>`。 */
    @Test
    fun splitsRowWhenSuccessorStartsNewTextInClosedCell() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.add(WRAP) // 縦継続シグナル（このセルだけ開いている）
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった")) // 閉じたテキストセル
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("鉄道"))
        words.add(WRAP)
        words.addAll(cell(84, TABLE_RIGHT, 0x0001)) // 完結セルで閉じた同一スパンへ新テキスト → 衝突
        words.addAll(text("天上"))
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertEquals(2, trCount(xml), "閉じたセル衝突で統合してはならない: $xml")
        assertContains(xml, "<tr><td>銀河</td><td>けぶった</td></tr>")
        assertContains(xml, "<tr><td>鉄道</td><td>天上</td></tr>")
        assertEquals("銀河けぶった鉄道天上", textOnly(xml), "テキスト全量保存則")
    }

    // ------------------------------------------------------------------
    // R3: 孤立罫（打ち消し線）両側の空白断片スパンの折込合体
    // ------------------------------------------------------------------

    /** 他行の宣言スパンに真包含される空白断片は包含スパンへ合体しグリッドを汚染しない。 */
    @Test
    fun foldsBlankRuleFragmentSpansIntoCoveringCell() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった"))
        words.add(WALL)
        words.addAll(rowHeader(n = 4))
        words.addAll(cell(8, 80))
        words.addAll(text("天上"))
        // 孤立罫で割れた両側断片（空白・幅 8）: [84,92] + [152,160] ⊂ [84,160]
        words.addAll(cell(84, 92))
        words.addAll(cell(152, TABLE_RIGHT))
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<tr><td>銀河</td><td>けぶった</td></tr>")
        assertContains(xml, "<tr><td>天上</td><td></td></tr>")
        assertEquals(0, Regex("colspan=").findAll(xml).count(), "断片による偽 colspan 発生: $xml")
        assertEquals("銀河けぶった天上", textOnly(xml), "テキスト全量保存則")
    }

    // ------------------------------------------------------------------
    // R4: 下方継続アンカー（語6=0x0002）と直下断片（語6=0x0000）の吸収
    // ------------------------------------------------------------------

    /** 継続アンカー真下の断片テキストは同一 tr の同一セルへ `<br/>` 継ぎで吸収する。 */
    @Test
    fun absorbsContinuationFragmentBelowAnchorCell() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.addAll(cell(84, TABLE_RIGHT, 0x00ff, 0x0002)) // 語7=0x0002 下方継続アンカー
        words.addAll(text("けぶった"))
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("天上"))
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertEquals(1, trCount(xml), "断片行を独立 tr にしてはならない: $xml")
        assertContains(xml, "<td>銀河</td><td>けぶった<br/>天上</td>")
        assertEquals("銀河けぶった天上", textOnly(xml), "テキスト全量保存則")
    }

    // ------------------------------------------------------------------
    // R5: ブロック列（空白+WRAP 先頭スパン）の rowspan 合体
    // ------------------------------------------------------------------

    /** 空白+WRAP 先頭セルで始まるブロックの同一スパン列を先頭 rowspan セルへ合体する。 */
    @Test
    fun mergesBlockColumnIntoRowspanCellAtBlockAnchor() {
        val words = mutableListOf<Int>()
        // ブロック先頭: 先頭スパンが空白+WRAP
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(8, 22))
        words.add(WRAP)
        words.addAll(cell(44, 80))
        words.addAll(text("朝"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("駅"))
        words.add(WALL)
        // ブロック継続: 同一スパンへ WRAP 末尾の断片テキスト
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(8, 22))
        words.addAll(text("銀河"))
        words.add(WRAP)
        words.addAll(cell(44, 80))
        words.addAll(text("夕"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("野"))
        words.add(WALL)
        // 次のブロック先頭（別ブロックの開始 = 前ブロックの終端。閉じたセルへ完結セルで
        // 新テキストを開始するため衝突 → 前ブロックはここで閉じる）
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(8, 22))
        words.add(WRAP)
        words.addAll(cell(44, 80, 0x0001))
        words.addAll(text("夜"))
        words.addAll(cell(84, TABLE_RIGHT, 0x0001))
        words.addAll(text("空"))
        words.add(WALL)
        // 2 ブロック目の継続行
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(8, 22))
        words.addAll(text("矢"))
        words.add(WRAP)
        words.addAll(cell(44, 80))
        words.addAll(text("刺"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("はね"))
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<tr><td rowspan=\"2\">銀河</td><td>朝</td><td>駅</td></tr>")
        assertContains(xml, "<tr><td>夕</td><td>野</td></tr>")
        assertContains(xml, "<tr><td rowspan=\"2\">矢</td><td>夜</td><td>空</td></tr>")
        assertContains(xml, "<tr><td>刺</td><td>はね</td></tr>")
        assertEquals(4, trCount(xml), "ブロック列は行を統合しない: $xml")
        assertEquals(2, Regex("<td\\b[^>]*rowspan").findAll(xml).count(), "ブロック先頭 2 個の rowspan: $xml")
        assertEquals("銀河朝駅夕野矢夜空刺はね", textOnly(xml), "テキスト全量保存則")
    }

    /** 広幅の空白+WRAP スパンはテキストを巻き上げず、行ごとの本文順を保つ。 */
    @Test
    fun wideBlockColumnsKeepTextInPlace() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.add(WRAP)
        words.addAll(cell(84, 160))
        words.addAll(text("銀河"))
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("鉄道"))
        words.add(WRAP)
        words.addAll(cell(84, 160))
        words.addAll(text("天上"))
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertEquals(2, trCount(xml))
        assertFalse(Regex("<td[^>]*rowspan").containsMatchIn(xml), "広幅列を巻き上げてはならない: $xml")
        assertContains(xml, "<tr><td></td><td>銀河</td></tr>")
        assertContains(xml, "<tr><td>鉄道</td><td>天上</td></tr>")
        assertEquals("銀河鉄道天上", textOnly(xml), "テキスト順・全量保存則")
    }

    /**
     * ブロック内の空白広幅スパンが他行の内部境界スパンを真包含なら
     * colspan×rowspan 単一セルへ合体する（統計表右側空白ブロック）。
     */
    @Test
    fun mergesWideBlankCellSpanningSubColumnsIntoBlockAnchor() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 4))
        words.addAll(cell(8, 22))
        words.add(WRAP)
        words.addAll(cell(44, 80))
        words.addAll(text("五"))
        words.addAll(cell(84, 160)) // 空白広幅（2 列分の領域）
        words.add(WALL)
        words.addAll(rowHeader(n = 4))
        words.addAll(cell(8, 22))
        words.addAll(text("銀河"))
        words.add(WRAP)
        words.addAll(cell(44, 80))
        words.addAll(text("六"))
        words.addAll(cell(84, 160))
        words.add(WALL)
        // ブロック外の行: 広幅スパンの内部境界を宣言する列分割
        words.addAll(rowHeader(n = 5))
        words.addAll(cell(8, 40))
        words.addAll(text("八"))
        words.addAll(cell(44, 80, 0x0001))
        words.addAll(text("九"))
        words.addAll(cell(84, 114))
        words.addAll(text("十"))
        words.addAll(cell(114, 136))
        words.addAll(text("一"))
        words.addAll(cell(136, 160))
        words.addAll(text("二"))
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<td rowspan=\"2\" colspan=\"3\"></td>")
        assertFalse(Regex("<td colspan=\"3\"></td>(?!</tr>)").containsMatchIn(xml), "内部 span 欠落: $xml")
        assertEquals(3, trCount(xml), "ブロック列は行を統合しない: $xml")
        assertEquals("銀河五六八九十一二", textOnly(xml), "テキスト全量保存則")
    }
}
