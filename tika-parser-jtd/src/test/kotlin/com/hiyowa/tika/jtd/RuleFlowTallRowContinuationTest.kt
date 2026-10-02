package com.hiyowa.tika.jtd

import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.TABLE_RIGHT
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.WALL
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.WRAP
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.cell
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.cfb
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.paragraph
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
 * RFC 0013 追補契約「縦継続セル（tall cell）の論理行統合」と「先頭前置きテキスト保存」の契約テスト。
 *
 * 実文書観察による確定事項（RFC 0013 追補 §13）:
 * - 同一スパン署名の物理行対（両物理行が各自 RowHeader を宣言）で、先行行のコンテンツセルがセル内 WRAP(0x000a) 末尾を
 *   1 つでも持つ場合、両物理行は同一論理行（縦に長い 1 セル）の折返しである。
 *   統合して 1 つの `<tr>` とし、継ぎ目は [FlowPart.LineBreak]（XHTML `<br/>`）で連結する。
 *   単独セル内 WRAP の半角スペース平坦化（§12.4 T3-1）は [FlowPart.Break] として不変。
 * - セル内 WRAP 末尾を持たない同一署名行は従来どおり別 `<tr>`（誤統合ガード）。
 * - セル内 WRAP は [FlowPart.Break] として半角スペース平坦化（§12.4 T3-1）と不変。
 * - `/DocumentText` が SsmgV.01 ヘッダ直後に TextV.01 名のない前置き本文（最初の
 *   RFC 0009 マーカー手前）を持つ場合、これを先頭 [FlowEvent.Text] として復元する。
 *   ヘッダ語 [4,10) と領域内不均匀（printable なし）は発火しない（リグレッションガード）。
 *
 * 本文文言は『銀河鉄道の夜』から引用（AGENTS.md 準拠）。
 */
class RuleFlowTallRowContinuationTest {

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

    /** SsmgV.01 ヘッダ w[4..9) 相当のフィラ語（先頭 magic は stream() が担う）。 */
    private val headerFiller = listOf(0x0000, 0x0003, 0x0000, 0x0100, 0x0000, 0x02f1)

    // ------------------------------------------------------------------
    // 縦継続セルの論理行統合
    // ------------------------------------------------------------------

    /** 同一スパン署名・先行行セル WRAP 末尾の物理行対は 1 論理行へ統合し `<br/>` で継ぐ。 */
    @Test
    fun mergesStackedPhysicalRowsIntoOneLogicalRow() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.add(WRAP) // 縦継続シグナル: このセルは次物理行へ折返る
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった"))
        words.add(WRAP)
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("鉄道"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("天上"))
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<tr><td>銀河<br/>鉄道</td><td>けぶった<br/>天上</td></tr>")
        assertEquals(1, Regex("<tr\\b").findAll(xml).count(), "物理行 2 行は 1 論理行でなければならない: $xml")
        assertEquals("銀河鉄道けぶった天上", textOnly(xml), "テキスト全量保存則")
    }

    /** 先行行に WRAP 末尾セルがない同一署名行対は統合しない（別 `<tr>` のまま。誤統合ガード）。 */
    @Test
    fun keepsRowsWithoutWrapSignalSeparate() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった"))
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("天上"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("白い"))
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertEquals(2, Regex("<tr\\b").findAll(xml).count(), "WRAP なし行は統合してはならない: $xml")
        assertContains(xml, "<tr><td>銀河</td><td>けぶった</td></tr>")
        assertContains(xml, "<tr><td>天上</td><td>白い</td></tr>")
    }

    /** 3 物理行の縦連鎖は同一論理行へ連結（継ぎ目毎 `<br/>`）。 */
    @Test
    fun chainsThreeStackedRowsIntoSingleLogicalRow() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.add(WRAP)
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった"))
        words.add(WRAP)
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("鉄道"))
        words.add(WRAP)
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("の夜"))
        words.add(WRAP)
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("天上"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("白い"))
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<tr><td>銀河<br/>鉄道<br/>天上</td><td>けぶった<br/>の夜<br/>白い</td></tr>")
        assertEquals(1, Regex("<tr\\b").findAll(xml).count(), "3 物理行は 1 論理行でなければならない: $xml")
    }

    /** 後続行の対応セルが空なら継ぎ目 `<br/>` を置かない（空折返し残渣禁止）。 */
    @Test
    fun mergesBlankContinuationCellWithoutLeadingBr() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.add(WRAP)
        words.addAll(cell(84, TABLE_RIGHT)) // 後続行でテキスト为空の縦長スパン
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("鉄道"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<tr><td>銀河<br/>鉄道</td><td></td></tr>")
        assertEquals(1, Regex("<tr\\b").findAll(xml).count())
    }

    /**
     * 同一セル内WRAP節をwrapでwrap - 論理行を分離（§12.4 T3-1）統合前の残留より。
     */
    @Test
    fun keepsSpanSignatureMismatchRowsSeparate() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.add(WRAP)
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった"))
        words.add(WRAP)
        words.add(WALL)
        // 全幅単一スパン行（WRAP ありでも署名不一致で別行）
        words.addAll(rowHeader(n = 1))
        words.addAll(cell(8, TABLE_RIGHT))
        words.addAll(text("銀河ステーション"))
        words.add(WRAP)
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertEquals(2, Regex("<tr\\b").findAll(xml).count(), "署名不一致行は統合してはならない: $xml")
        assertContains(xml, "<tr><td>銀河</td><td>けぶった</td></tr>")
        assertContains(xml, "<tr><td>銀河ステーション</td></tr>")
    }

    // ------------------------------------------------------------------
    // 先頭前置きテキスト保存（TextV.01 名なし SsmgV.01）
    // ------------------------------------------------------------------

    /** SsmgV.01 ヘッダ直後・最初のマーカー手前の前置き本文を先頭 Text で復元する。 */
    @Test
    fun restoresPrologueTextBeforeFirstMarker() {
        val words = mutableListOf<Int>()
        words.addAll(headerFiller)
        words.addAll(text("銀河鉄道の夜"))
        words.add(WRAP)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河帯"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった"))
        words.add(WALL)
        words.addAll(transition())

        val data = stream(words)
        val events = RuleFlowParser.parse(data)
        assertEquals(FlowEvent.Text("銀河鉄道の夜"), events.first(), "前置きテキストが先頭 Text でない: $events")

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to data)))
        val pIndex = xml.indexOf("<p>銀河鉄道の夜</p>")
        val tableIndex = xml.indexOf("<table")
        assertTrue(pIndex >= 0, "前置き <p> が欠落: $xml")
        assertTrue(tableIndex >= 0 && pIndex < tableIndex, "前置き段落は表より前に emit されなければならない: $xml")
        assertEquals(1, Regex("<p\\b").findAll(xml).count(), "前置きが既存段落群に汚染されてはならない: $xml")
        assertEquals("銀河鉄道の夜銀河帯けぶった", textOnly(xml), "テキスト全量保存則")
    }

    /** 前置き領域が空白のみの場合、先頭 Text を発火しない（リグレッションガード）。 */
    @Test
    fun doesNotEmitPrologueForWhitespaceOnlyRegion() {
        val words = mutableListOf<Int>()
        words.addAll(headerFiller)
        words.addAll(listOf(0x3000, 0x3000, 0x3000)) // 空白のみ: printable なし
        words.add(WRAP)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河帯"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった"))
        words.add(WALL)
        words.addAll(transition())

        val events = RuleFlowParser.parse(stream(words))
        assertFalse(events.first() is FlowEvent.Text, "空白のみ前置きで Text を発火してはならない: $events")
    }

    /** マーカーが語 10 未満（従来型フィクスチャ）は前置き経路が発火しない。 */
    @Test
    fun prologueGateRequiresMarkerBeyondHeader() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2)) // firstMarker == 4 < 10
        words.addAll(cell(8, 80))
        words.addAll(text("銀河帯"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった"))
        words.add(WALL)

        val events = RuleFlowParser.parse(stream(words))
        assertTrue(events.first() is FlowEvent.RowHeader, "語 4 マーカー文書で前置き Text を発火してはならない: $events")
    }

    /** TextV.01 混在型前置きは既存経路が担い、新経路は二重 emit しない。 */
    @Test
    fun noDoublePrologueForMixedTextvSegment() {
        val data = RuleFlowFixtureBuilder.rawTextSegment("銀河鉄道の夜")
        val events = RuleFlowParser.parse(data)
        val texts = events.filterIsInstance<FlowEvent.Text>().map { it.text }
        assertEquals(listOf("銀河鉄道の夜"), texts, "混在型 raw 前置きの二重 emit: $events")
    }

    /** 段落と表が混在する前置き: 前置き段落は最初の行ヘッダで確定し表に汚染されない。 */
    @Test
    fun prologueParagraphClosesBeforeTableRowHeaderContent() {
        val words = mutableListOf<Int>()
        words.addAll(headerFiller)
        words.addAll(text("午后の授業"))
        words.add(WRAP)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河帯"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった"))
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<p>午后の授業</p>")
        assertContains(xml, "<tr><td>銀河帯</td><td>けぶった</td></tr>")
        assertEquals("午后の授業銀河帯けぶった", textOnly(xml))
    }

    /** 段落ヘッダ先行（従来型）: 前置き新経路は paragraph テキストを重複保存しない。 */
    @Test
    fun paragraphRecordTextStaysInParagraphNotPrologue() {
        val words = mutableListOf<Int>()
        words.addAll(paragraph(0x0026))
        words.addAll(text("銀河ステーション"))
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河帯"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった"))
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<p>銀河ステーション</p>")
        assertEquals(1, Regex("<p\\b").findAll(xml).count(), "段落テキストの重複段落化禁止: $xml")
    }

    // ------------------------------------------------------------------
    // セル内 WRAP 平坦化（T3-1）不変の連続性
    // ------------------------------------------------------------------

    /** 同一セル内複数 WRAP junction・セル内 Wrap Break の cell span wrapは統合経路を污染しない。 */
    @Test
    fun intraCellWrapFlatteningUnchangedBesideMerge() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.add(WRAP)
        words.addAll(text("鉄道")) // 同一セル内折返し: 半角スペース平坦化（T3-1）
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった"))
        words.add(WRAP)
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("天上"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("白い"))
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<tr><td>銀河 鉄道<br/>天上</td><td>けぶった<br/>白い</td></tr>")
        assertEquals(1, Regex("<tr\\b").findAll(xml).count())
    }

    // ------------------------------------------------------------------
    // tall cell refinement 断片吸収（追補 §13.2 拡張・RFC 0013 追補 §15 系譜）
    // ------------------------------------------------------------------

    /**
     * 点線罫等で tall cell（縦長セル）が断片物理行で再宣言されない版面（span refinement）:
     * tall cell 列スパンの欠落で rowSig 不一致になっても、先行行が WRAP 継続中なら
     * 断片行を同一論理行へ吸収する（実文書フィンガープリント: 月 tall cell 未再宣言）。
     * 完結フラグ（語6=0x0001）の新タスク行は従来どおり別 `<tr>`（R2 ガード不変）。
     */
    @Test
    fun absorbsFragmentRowOmittingTallCellSpan() {
        val words = mutableListOf<Int>()
        // 物理行1: 月 tall cell（折返しを運ぶコンテンツ列と共存）
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(4, 8))
        words.addAll(text("銀河"))
        words.addAll(cell(12, 76))
        words.addAll(text("カムパネルラ"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("午后の授業"))
        words.add(WRAP)
        words.add(WALL)
        // 断片物理行: tall cell スパン未再宣言。コンテンツ列が折返し継続
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(12, 76))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("でてきた"))
        words.add(WRAP)
        words.add(WALL)
        // 新タスク物理行: 完結フラグ衝突で別 <tr>（ガード）
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(12, 76, flags = 0x0001))
        words.addAll(text("ジョバンニ"))
        words.addAll(cell(84, TABLE_RIGHT, flags = 0x0001))
        words.addAll(text("しずか"))
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        // tall cell 列は §14 R5 のブロック列 rowspan 化があり得るため属性は許容し、
        // 契約の要点（断片行が先行論理行へ吸収され折返し継ぎ目が <br/> で連結される）を固定する。
        assertTrue(
            Regex("<tr><td[^>]*>銀河</td><td>カムパネルラ</td><td>午后の授業<br/>でてきた</td></tr>")
                .containsMatchIn(xml),
            "断片吸収後の論理行結合: $xml",
        )
        assertEquals(2, Regex("<tr\\b").findAll(xml).count(), "tall cell 断片は先行論理行へ吸収: $xml")
        assertFalse(xml.contains("<tr><td>でてきた</td>"), "断片が行頭に standalone 化: $xml")
        assertEquals("銀河カムパネルラ午后の授業でてきたジョバンニしずか", textOnly(xml), "テキスト全量保存則")
    }

    /**
     * 追補 §15B 継続字下げ吸収: WRAP 語を持たず WALL 分割だけで折返す版面
     * （openContinued=false になる）で、断片行の全非空テキストが行頭字下げ
     * （全角スペース begin）なら同一論理行へ吸収する（RFC 0013 追補 §15B）。
     * 字下げのない新テキストの再出現は新論理行（ガード）。
     */
    @Test
    fun absorbsIndentedContinuationRowWithoutWrapSignal() {
        val words = mutableListOf<Int>()
        // 物理行1: WRAP 終端ナシ（WALL だけで折返す実文書パターン）
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(4, 8))
        words.addAll(text("銀河"))
        words.addAll(cell(12, 76))
        words.addAll(text("カムパネルラ"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("午后の授業を"))
        words.add(WALL)
        // 断片物理行: 同一 span 再宣言・字下げ継続テキスト
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(4, 8))
        words.addAll(cell(12, 76))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("　でてきた"))
        words.add(WALL)
        // 新レコード物理行: 字下げなし新テキスト → 別 <tr>（ガード）
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(4, 8))
        words.addAll(cell(12, 76))
        words.addAll(text("ジョバンニ"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("しずか"))
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertTrue(
            Regex("<tr><td[^>]*>銀河</td><td>カムパネルラ</td><td>午后の授業を<br/>　でてきた</td></tr>")
                .containsMatchIn(xml),
            "字下げ継続の吸収結合: $xml",
        )
        assertEquals(2, Regex("<tr\\b").findAll(xml).count(), "字下げ継続断片は先行論理行へ吸収: $xml")
        assertFalse(xml.contains("<tr><td></td><td></td><td>　でてきた</td></tr>"), "断片 standalone 化: $xml")
    }
}
