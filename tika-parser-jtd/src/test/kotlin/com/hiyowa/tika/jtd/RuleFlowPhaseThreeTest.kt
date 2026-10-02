package com.hiyowa.tika.jtd

import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.TABLE_RIGHT
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.WALL
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.cell
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.cfb
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.paragraph
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.rowHeader
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.stream
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.text
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.ToMarkdownContentHandler
import org.apache.tika.sax.ToXMLContentHandler
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC 0013 Phase 3（§12 docker 往復ラウンド）契約テスト:
 * T3-2 表の継続判定（suspend）と T3-3 縦結合近似（rowspan）。
 *
 * T3-2（§12.4）: 表組内部に挟まる段落ヘッダ（class 0x0010・w4≠0x008f）で即 close しない。
 * 表は一時停止（suspend）し、後続 WALL 区間がスパンを再宣言するなら同一表として継続する。
 * グリッド外のテキスト区間・SectionTransition・EOS でのみ終了する。表内の全幅単一スパンは
 * テキストが空なら区切り罫（§9.11 孤立罫の延長）として行を作らず表を開いたまま扱い、
 * テキストを運ぶなら全列 colspan 行になる。
 *
 * T3-3（§12.4）: 後続行で同一グリッド槽が欠落し、前行の該当セルがテキストを運ぶとき
 * `rowspan` 近似を付与する（§5.3 縦結合近似・XHTML 側。Markdown は平坦化丢・§5.2 非対称）。
 * 同一槽がテキストを伴って毎行立つ申請書型（反復申請槽型）は縦結合しない（誤検出ガード）。
 *
 * 本文文言は『銀河鉄道の夜』から引用（AGENTS.md 準拠）。
 */
class RuleFlowPhaseThreeTest {

    private fun parseXhtml(data: ByteArray): String {
        val handler = ToXMLContentHandler()
        TikaInputStream.get(ByteArrayInputStream(data)).use { stream ->
            JtdParser().parse(stream, handler, Metadata(), ParseContext())
        }
        return compact(handler.toString())
    }

    private fun parseMarkdown(data: ByteArray): String {
        val handler = ToMarkdownContentHandler()
        TikaInputStream.get(ByteArrayInputStream(data)).use { stream ->
            JtdParser().parse(stream, handler, Metadata(), ParseContext())
        }
        return handler.toString()
    }

    private fun compact(xml: String): String =
        xml
            .replace(Regex("<br\\s*/>\\s*"), "<br/>")
            .replace(Regex(">\\s+<"), "><")
            .trim()

    private fun textOnly(xml: String): String =
        xml.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), "")

    private fun assertWellFormed(xml: String) {
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    private fun tableCount(xml: String): Int = Regex("<table\\b").findAll(xml).count()

    // ── T3-2 表の継続判定（suspend） ─────────────────────────────

    /** 段落ヘッダ挟みで閉じず 1 表に継続する（§12.3 要因 2 の解消・全区間ヘッダ型）。 */
    @Test
    fun continuesTableAcrossParagraphHeaderSandwich() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河帯"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("白い"))
        words.add(WALL)
        // 表組内部に挟まる段落ヘッダ（全 WALL 区間頭にヘッダが立つ文書構造）
        words.addAll(paragraph(0x0026))
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("天上"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった"))
        words.add(WALL)
        words.addAll(paragraph(0x0026))
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("シグナル"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("風"))
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertEquals(1, tableCount(xml), "段落ヘッダ挟みで表が分裂してはならない: $xml")
        assertEquals(3, Regex("<tr>").findAll(xml).count())
        assertContains(xml, "<tr><td>銀河帯</td><td>白い</td></tr>")
        assertContains(xml, "<tr><td>天上</td><td>けぶった</td></tr>")
        assertEquals(0, Regex("<p>").findAll(xml).count(), "表組内段落ヘッダで <p> を立てない")
        assertEquals("銀河帯白い天上けぶったシグナル風", textOnly(xml))
    }

    /** 表内の全幅単一スパンでテキストが空なら区切り罫（行を作らず表を開いたまま）。 */
    @Test
    fun fullWidthBlankSpanIsSeparatorWithoutRow() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河帯"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("白い"))
        words.add(WALL)
        words.addAll(paragraph(0x0026))
        words.addAll(cell(0, TABLE_RIGHT)) // 全幅・テキストなし = 孤立罫（§9.11）
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("天上"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった"))
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertEquals(1, tableCount(xml), "区切り罫で表が分裂してはならない: $xml")
        assertEquals(2, Regex("<tr>").findAll(xml).count(), "空の全幅スパンは行を作らない: $xml")
        assertEquals("銀河帯白い天上けぶった", textOnly(xml))
    }

    /** 表内の全幅単一スパンがテキストを運ぶなら全列 colspan 行になる（跨ぎタイトル型）。 */
    @Test
    fun textBearingFullWidthSpanBecomesColspanRowInsideTable() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河帯"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("白い"))
        words.add(WALL)
        words.addAll(paragraph(0x0026))
        words.addAll(cell(0, TABLE_RIGHT)) // 全幅スパンにテキスト（罫線上跨ぎ）
        words.addAll(text("天の川"))
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("天上"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった"))
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertEquals(1, tableCount(xml), "跨ぎテキストで表が分裂してはならない: $xml")
        assertEquals(3, Regex("<tr>").findAll(xml).count())
        assertTrue(
            Regex("<tr><td colspan=\"\\d+\">天の川</td></tr>").containsMatchIn(xml),
            "全幅テキスト行は全列 colspan 行になる: $xml",
        )
        assertEquals(0, Regex("<p>").findAll(xml).count(), "表組内テキストを <p> にしない")
        assertWellFormed(xml)
        assertEquals("銀河帯白い天の川天上けぶった", textOnly(xml))
    }

    /** グリッド外のテキスト区間は表を終了させる（§3.7 v2・§12.4 終了条件）。 */
    @Test
    fun closesTableOnSpanlessTextOutsideGrid() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河帯"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("白い"))
        words.add(WALL)
        // グリッド外のテキスト区間（スパン宣言なし）
        words.addAll(paragraph(0x0026))
        words.addAll(text("カムパネルラ"))
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertEquals(1, tableCount(xml))
        val tableEnd = xml.indexOf("</table>")
        val textIndex = xml.indexOf("カムパネルラ")
        assertTrue(tableEnd in 0 until textIndex, "グリッド外テキストは表の後に書く: $xml")
        assertContains(xml, "<p>カムパネルラ</p>")
        assertEquals("銀河帯白いカムパネルラ", textOnly(xml))
    }

    /** 誤検出ガード回帰禁止: 罫線なし（段落ヘッダのみ）文書で表を開かない（§3.5）。 */
    @Test
    fun neverOpensTableWithoutSpansEvenWithSuspend() {
        val words = mutableListOf<Int>()
        repeat(3) {
            words.addAll(paragraph(0x0020))
            words.addAll(text("銀河鉄道の夜"))
            words.add(WALL)
        }
        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertFalse(xml.contains("<table"), "スパンなしで表を開いてはならない")
        assertEquals("銀河鉄道の夜銀河鉄道の夜銀河鉄道の夜", textOnly(xml))
    }

    // ── T3-3 縦結合近似（rowspan） ──────────────────────────────

    /** 後続行で同一グリッド槽が欠落し前行セルがテキストを運ぶなら rowspan 近似（§5.3）。 */
    @Test
    fun approximatesRowspanWhenSlotDropsOut() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 44))
        words.addAll(text("一"))
        words.addAll(cell(48, 84))
        words.addAll(text("二"))
        words.add(WALL)
        // 槽 48 が欠落（縦結合の継続マス）・槽 8 はテキストを伴う
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 44))
        words.addAll(text("三"))
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 44))
        words.addAll(text("四"))
        words.addAll(cell(48, 84))
        words.addAll(text("五"))
        words.add(WALL)

        val data = cfb(mapOf("/DocumentText" to stream(words)))
        val xml = parseXhtml(data)
        assertContains(xml, "<td rowspan=\"2\">二</td>")
        assertContains(xml, "<tr><td>三</td></tr>")
        assertWellFormed(xml)
        assertEquals("一二三四五", textOnly(xml))
    }

    /** 同一槽がテキストを伴って毎行立つ申請書型（反復申請槽型）は縦結合しない（誤検出ガード）。 */
    @Test
    fun noRowspanWhenSlotCarriesTextInEveryRow() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 128))
        words.addAll(text("手をあげました"))
        words.addAll(cell(132, TABLE_RIGHT))
        words.addAll(text("カムパネルラ"))
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 128))
        words.addAll(text("四五人"))
        words.addAll(cell(132, TABLE_RIGHT))
        words.addAll(text("あげた"))
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 128))
        words.addAll(text("黒板"))
        words.addAll(cell(132, TABLE_RIGHT))
        words.addAll(text("星座の図"))
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertFalse(xml.contains("rowspan"), "毎行テキストを運ぶ槽は縦結合しない: $xml")
        assertEquals(3, Regex("<tr>").findAll(xml).count())
    }

    /** 前行の該当セルが空なら rowspan を付けない（縦結合アンカーはテキスト必須）。 */
    @Test
    fun noRowspanForBlankAnchorCell() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 44))
        words.addAll(text("一"))
        words.addAll(cell(48, 84)) // 空セル
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 44))
        words.addAll(text("三"))
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertFalse(xml.contains("rowspan"), "空セルに rowspan を付けない: $xml")
        assertEquals(2, Regex("<tr>").findAll(xml).count())
    }

    /** colspan と rowspan の併記（§12.4 T3-3 併記可）。 */
    @Test
    fun colspanAndRowspanCoexist() {
        // 槽 {8,48,88}: r1 の [48,124]（colspan 2）が r2 で両槽欠落 → rowspan=2。
        // r3 で槽 88 が別のセルとして再出現する（部分欠落の打ち切り側面も担保）。
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(8, 44))
        words.addAll(text("一"))
        words.addAll(cell(48, 124))
        words.addAll(text("二"))
        words.add(WALL)
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(8, 44))
        words.addAll(text("三"))
        words.add(WALL)
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(8, 44))
        words.addAll(text("四"))
        words.addAll(cell(88, TABLE_RIGHT))
        words.addAll(text("五"))
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertTrue(
            Regex("<td rowspan=\"2\"[^>]*colspan=\"2\"[^>]*>二</td>").containsMatchIn(xml),
            "colspan/rowspan 併記: $xml",
        )
        assertContains(xml, "<tr><td>三</td></tr>")
        assertWellFormed(xml)
        assertEquals("一二三四五", textOnly(xml))
    }

    /** 連続欠落槽は補間を連結せず槽ごとに空セルを出す（GFM パイプ均一性）。 */
    @Test
    fun padsConsecutiveMissingSlotsPerSlot() {
        // row1 の槽 48/88 は空セル（アンカーにならない）→ 次行の欠落は rowspan ではなく補間
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(8, 44))
        words.addAll(text("一"))
        words.addAll(cell(48, 84))
        words.addAll(cell(88, TABLE_RIGHT))
        words.add(WALL)
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(8, 44))
        words.addAll(text("四"))
        words.add(WALL)

        val data = cfb(mapOf("/DocumentText" to stream(words)))
        val xml = parseXhtml(data)
        assertFalse(xml.contains("rowspan"), "空 anchor に rowspan を付けない: $xml")
        assertContains(xml, "<tr><td>四</td><td></td><td></td></tr>")
        val md = parseMarkdown(data)
        val pipeLines = md.lines().filter { it.trimStart().startsWith("|") }
        val headerPipes = pipeLines.first().count { it == '|' }
        pipeLines.forEachIndexed { i, line ->
            assertEquals(headerPipes, line.count { it == '|' }, "セル数不一致 行$i: $line")
        }
    }

    /** 2 連続行の欠落は rowspan=3 として縦に伸ばす（アンカーは最初のテキストセル）。 */
    @Test
    fun extendsRowspanAcrossMultipleDroppedRows() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 44))
        words.addAll(text("銀河"))
        words.addAll(cell(48, 84))
        words.addAll(text("望遠鏡"))
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 44))
        words.addAll(text("天上"))
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 44))
        words.addAll(text("けぶった"))
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<td rowspan=\"3\">望遠鏡</td>")
        assertContains(xml, "<tr><td>天上</td></tr>")
        assertContains(xml, "<tr><td>けぶった</td></tr>")
        assertWellFormed(xml)
        assertEquals("銀河望遠鏡天上けぶった", textOnly(xml))
    }
}
