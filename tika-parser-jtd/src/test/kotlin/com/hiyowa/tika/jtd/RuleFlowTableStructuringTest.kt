package com.hiyowa.tika.jtd

import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.TABLE_RIGHT
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.WALL
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.WRAP
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.cell
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.cfb
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.docItemInfo
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.layoutBoxStream
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.markerText
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.paragraph
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.rowHeader
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.stream
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.text
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.transition
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import org.apache.tika.sax.ToMarkdownContentHandler
import org.apache.tika.sax.ToXMLContentHandler
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC 0013 v0.3.0「一太郎罫線の構造化」SAX 出力契約テスト（Phase 1+2）。
 *
 * 合成バイナリ（RFC 0009 レコード・§9.7 統制サンプル準拠）を JtdParser に通し、
 * XHTML の `<p>` / `<table>` / `<tr>` / `<td>` 構造と以下を検証する:
 * - 確定的行境界: WALL(0x000e)=`<tr>` 区間（RFC 0013 §3.4）
 * - 同一スパン coalesce・境界ストリップ除外・グリッド槽 padding・colspan 近似
 * - 表 open/close 判定と誤検出ガード（RFC 0013 §3.5/§3.7）
 * - テキスト全量保存則（タグ除去＋全空白除去で一致）
 *
 * 本文文言は『銀河鉄道の夜』から引用（AGENTS.md 準拠）。
 */
class RuleFlowTableStructuringTest {

    private fun parseXhtml(data: ByteArray): String {
        val handler = ToXMLContentHandler()
        TikaInputStream.get(ByteArrayInputStream(data)).use { stream ->
            JtdParser().parse(stream, handler, Metadata(), ParseContext())
        }
        return compact(handler.toString())
    }

    private fun parseBodyText(data: ByteArray): String {
        val handler = BodyContentHandler(-1)
        TikaInputStream.get(ByteArrayInputStream(data)).use { stream ->
            JtdParser().parse(stream, handler, Metadata(), ParseContext())
        }
        return handler.toString()
    }

    /**
     * serialization 差分を吸収して構造だけ Assert できるように正規化する。
     * Tika 4 の XHTMLContentHandler は ENDLINE 要素（br 等）の直後に整形改行を挿入する
     * ため、その見た目の空白・自己閉じ表記（`<br />`）の揺れ here で吸収する（契約は構造）。
     */
    private fun compact(xml: String): String =
        xml
            .replace(Regex("<br\\s*/>\\s*"), "<br/>")
            .replace(Regex(">\\s+<"), "><")
            .trim()

    private fun textOnly(xml: String): String =
        xml.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), "")

    private fun twoRowTableFixture(): ByteArray {
        val words = mutableListOf<Int>()
        words.addAll(paragraph(0x0026))
        words.addAll(text("一、午后の授業"))
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(0, 4)) // 境界ストリップ（幅4）→ 除外
        words.addAll(cell(8, 80))
        words.addAll(text("銀河帯"))
        words.addAll(cell(84, 160))
        words.addAll(text("けぶった"))
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(0, 4))
        words.addAll(cell(8, 80))
        words.addAll(text("天上"))
        words.addAll(cell(84, 160))
        words.addAll(text("白い"))
        words.add(WALL)
        words.addAll(transition())
        words.addAll(paragraph(0x0026))
        words.addAll(text("二、活版所"))
        return cfb(mapOf("/DocumentText" to stream(words)))
    }

    @Test
    fun extractsStableGridTableIntoXhtml() {
        val xml = parseXhtml(twoRowTableFixture())

        assertContains(xml, "<p>一、午后の授業</p>")
        assertContains(xml, "<table><tbody>")
        assertContains(xml, "<tr><td>銀河帯</td><td>けぶった</td></tr>")
        assertContains(xml, "<tr><td>天上</td><td>白い</td></tr>")
        assertContains(xml, "</tbody></table>")
        assertContains(xml, "<p>二、活版所</p>")
        assertEquals(2, Regex("<tr>").findAll(xml).count())
        assertFalse(xml.contains("<hr"), "0x000c なしで hr を立ててはならない（RFC 0013 §2.1）")
        assertEquals(
            "一、午后の授業銀河帯けぶった天上白い二、活版所",
            textOnly(xml),
            "テキスト全量保存則",
        )
    }

    @Test
    fun coalescesSameSpanReaffirmWithinSegment() {
        // 同一セグメント内で同一スパンが反復宣言されたら同一物理セルに coalesce（§3.2）
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.addAll(cell(8, 80)) // 同一スパン reaffirm
        words.addAll(text("鉄道"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("夜"))
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<tr><td>銀河鉄道</td><td>夜</td></tr>")
    }

    /**
     * T3-1（§12.4）: セル内 WRAP は `<br/>` にしない。
     * SAX は 1 本のパイプであり ToMarkdownContentHandler（commonmark）はセル内 br を
     * ハードブレーク（`  \n`）として出力し GFM 表行を分断する（§12.3 主因）。
     * 決定契約: セル内改行は半角スペース 1 個へ平坦化（連続 BR は 1 個に集約、
     * 先頭・末尾の Blank Wrap は破棄）。段落内 `<br/>` は据え置き（後述）。
     */
    @Test
    fun flattensWrapInsideCellToSingleSpace() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.add(WRAP) // セル内折返し → 半角スペース平坦化（§12.4 T3-1）
        words.add(WRAP) // 連続 WRAP は 1 個に集約
        words.addAll(text("鉄道"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("夜空"))
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<td>銀河 鉄道</td>")
        assertFalse(Regex("<td[^>]*>[^<]*<br").containsMatchIn(xml), "セル内に br を emit してはならない: $xml")
        assertEquals("銀河鉄道夜空", textOnly(xml), "テキスト全量保存則（空白は正規化で吸収）")
    }

    /** T3-1: 前後にテキストがない BR（Blank Wrap 積み・空セル）は破棄する。 */
    @Test
    fun discardsBlankWrapsAsEmptyCells() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.add(WRAP)
        words.add(WRAP) // 先頭 BR → 落ちる
        words.addAll(text("銀河"))
        words.add(WRAP) // 末尾 BR（スパン間前進マーカー）→ 落ちる
        words.addAll(cell(84, TABLE_RIGHT)) // テキストなし = 空セル
        words.add(WRAP)
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<tr><td>銀河</td><td></td></tr>")
        assertFalse(Regex("<td[^>]*>[^<]*<br").containsMatchIn(xml), "セル内に br を emit してはならない: $xml")
    }

    /** T3-1: 段落内の `<br/>` は据え置き（ブロックレベルは Markdown で破綻しない）。 */
    @Test
    fun keepsParagraphLevelBr() {
        val words = mutableListOf<Int>()
        words.addAll(paragraph(0x0026))
        words.addAll(text("銀河"))
        words.add(WRAP)
        words.addAll(text("鉄道の夜"))
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertFalse(xml.contains("<table"))
        assertTrue(
            Regex("<p>銀河<br\\s*/>鉄道の夜</p>").containsMatchIn(xml),
            "段落内 WRAP は <br/> を維持する: $xml",
        )
    }

    /**
     * T3-1 受入基準（合成フィクスチャ版・§12.5）: Markdown 整形性。
     * セル内 WRAP と欠落槽补完を含む表を `ToMarkdownContentHandler` に通し、
     *分行断片ゼロ・全データ行のセル数がヘッダ行（区切り行）と一致を検証する。
     */
    @Test
    fun markdownPipeRowsStayIntactAcrossFlattenedCells() {
        val words = mutableListOf<Int>()
        // 3 槽グリッド（8/48/88）
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(8, 44))
        words.addAll(text("銀河"))
        words.add(WRAP)
        words.addAll(text("鉄道"))
        words.addAll(cell(48, 84))
        words.addAll(text("夜"))
        words.addAll(cell(88, TABLE_RIGHT)) // 空槽（跨ぎなし・補間対象）
        words.add(WALL)
        // 2 槽分の行: 槽 88 欠落 → 空セル補間（GFM 均一性）
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(8, 44))
        words.addAll(text("天上"))
        words.addAll(cell(48, 84))
        words.addAll(text("白い"))
        words.add(WALL)
        words.addAll(transition())

        val data = cfb(mapOf("/DocumentText" to stream(words)))
        val handler = ToMarkdownContentHandler()
        TikaInputStream.get(ByteArrayInputStream(data)).use { s ->
            JtdParser().parse(s, handler, Metadata(), ParseContext())
        }
        val md = handler.toString()
        val pipeLines = md.lines().filter { it.trimStart().startsWith("|") }
        // tr 2 個の表: ヘッダ行 + 区切り行 + データ 1 行（1 行目の tr が GFM ヘッダ）
        assertTrue(pipeLines.size >= 3, "パイプ表にならなければならない: $md")
        // 分行断片ゼロ: テーブル領域に pipe で始まらない行混入なし（このフィクスチャは表のみ）
        assertEquals(md.trim().lines().size, pipeLines.size, "pipe 外の断片行: $md")
        val headerCells = pipeLines.first().count { it == '|' }
        pipeLines.forEachIndexed { i, line ->
            assertEquals(headerCells, line.count { it == '|' }, "セル数不一致 行$i: $line")
        }
        assertContains(md, "銀河 鉄道")
        assertFalse(md.contains("  \n"), "Markdown にハードブレークが残ってはならない")
    }

    @Test
    fun doesNotOpenTableWithoutCellRecords() {
        // WALL 単独では表を開かない（RFC 0013 §3.5 ガード・孤立 WALL 型）
        val words = mutableListOf<Int>()
        words.addAll(paragraph(0x0020))
        words.addAll(text("銀河鉄道の夜"))
        words.add(WALL)
        words.addAll(paragraph(0x0020))
        words.addAll(text("宮沢賢治"))

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertFalse(xml.contains("<table"), "セルレコードなしで表を開いてはならない")
        assertEquals(2, Regex("<p>").findAll(xml).count(), "WALL で段落が区切られる")
        assertEquals("銀河鉄道の夜宮沢賢治", textOnly(xml))
    }

    @Test
    fun doesNotOpenTableForFullWidthSingleSpan() {
        // 全幅単一スパン（行間横罫型）は段落に射影（§3.5 誤検出ガード）
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 1))
        words.addAll(cell(0, TABLE_RIGHT))
        words.addAll(text("銀河帯"))
        words.add(WALL)
        words.addAll(rowHeader(n = 1))
        words.addAll(cell(0, TABLE_RIGHT))
        words.addAll(text("天上"))
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertFalse(xml.contains("<table"), "coalesce 後 1 スパンでは表を開かない")
        assertEquals("銀河帯天上", textOnly(xml))
    }

    @Test
    fun doesNotOpenTableForSealFrameStyleUnknownRecords() {
        // 破線シール枠系の未確認クラス（0x0017）は既知クラス外 → 表を開かない（§9.8）
        val frameRecord = RuleFlowFixtureBuilder.record(0x0010, listOf(0x0000, 0x0017, 0x0013, 0x0000, 0x0000, 0x0006, 0x0000))
        val words = mutableListOf<Int>()
        words.addAll(frameRecord)
        words.addAll(text("天気輪の柱"))
        words.add(WALL)
        words.addAll(frameRecord)
        words.addAll(text("銀河"))
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertFalse(xml.contains("<table"), "w4=0x0017 枠系ヘッダ＋スパンなしで表を開かない")
        assertEquals("天気輪の柱銀河", textOnly(xml))
    }

    @Test
    fun opensTableOnStableGridWithZeroW4Headers() {
        // w4=0x0000 行ヘッダ（ルートシート型）でも安定グリッド反復で表を開く（§3.5 主シグナル）
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2, w4 = 0x0000))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("夜"))
        words.add(WALL)
        words.addAll(rowHeader(n = 2, w4 = 0x0000))
        words.addAll(cell(8, 80))
        words.addAll(text("天上"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("白い"))
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<table><tbody>")
        assertContains(xml, "<tr><td>銀河</td><td>夜</td></tr>")
        assertContains(xml, "<tr><td>天上</td><td>白い</td></tr>")
    }

    @Test
    fun approximatesColspanAcrossGridSlots() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(0, 4)) // strip
        words.addAll(cell(8, 44)) // 左辺 {8,28} を跨ぐ → colspan=2
        words.addAll(text("一"))
        words.addAll(cell(48, 84))
        words.addAll(text("二"))
        words.add(WALL)
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(0, 4))
        words.addAll(cell(8, 24))
        words.addAll(text("三"))
        words.addAll(cell(28, 44))
        words.addAll(text("四"))
        words.addAll(cell(48, 84))
        words.addAll(text("五"))
        words.add(WALL)
        words.addAll(transition())

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<tr><td colspan=\"2\">一</td><td>二</td></tr>")
        assertContains(xml, "<tr><td>三</td><td>四</td><td>五</td></tr>")
    }

    @Test
    fun keepsTextBearingNarrowCellInTable() {
        // 狭幅セルでもテキストを運ぶなら保持する（§2.2 全量保存則・記入欄の一文字幅マス）
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(0, 4)) // 狭幅だが「印」を運ぶ → 保持
        words.addAll(text("印"))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("夜空"))
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<tr><td>印</td><td>銀河</td><td>夜空</td></tr>")
    }

    @Test
    fun keepsMixedPrologueBeforeFirstMarker() {
        // 混在型（P1）: 最初のマーカーより手前の raw 前置きを失わず保持する（§2.2 全量保存則）。
        // 語10..13 に "TextV.01"、語14=0、語15=spanLen、語16.. に span（前置き＋マーカー本文）。
        val segName = intArrayOf(0x5465, 0x7874, 0x562E, 0x3031) // "TextV.01"
        val span = mutableListOf<Int>()
        span.addAll(text("宮沢賢治"))        // 前置き（最初のマーカー 0x001c より手前）
        span.addAll(paragraph(0x0026))       // 最初のマーカー（0x001c 段落ヘッダ）
        span.addAll(text("一、午后の授業"))
        span.add(WALL)
        val words = mutableListOf<Int>()
        words.addAll(listOf(0, 0, 0, 0, 0, 0)) // 語4..9 = 0（語9 は raw-text 個数にせず混在型）
        words.addAll(segName.toList())         // 語10..13 "TextV.01"
        words.add(0x0000)                      // 語14
        words.add(span.size)                   // 語15 = spanLen
        words.addAll(span)                     // 語16.. = span
        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        val only = textOnly(xml)
        assertContains(only, "宮沢賢治")
        assertContains(only, "一、午后の授業")
    }

    @Test
    fun abandonsPendingRowsAsParagraphWhenHeaderOpensTable() {
        // 表 open（w4=0x008f セグメント）時に pending へ積まれた未確定行のテキストが
        // 捨てられず、表より前の段落として保持される（§2.2 保存則優先）。
        val words = mutableListOf<Int>()
        // seg1: w4=0x0000（RowHeader 判定なし）・グリッド [8,48]・テキストを運ぶ 2 セル
        words.addAll(rowHeader(n = 2, w4 = 0x0000))
        words.addAll(cell(8, 44))
        words.addAll(text("銀河"))
        words.addAll(cell(48, 84))
        words.addAll(text("夜"))
        words.add(WALL)
        // seg2: w4=0x008f（表 open）・グリッド [8,84]
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("天上"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("白い"))
        words.add(WALL)

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<table")
        assertTrue(xml.contains("銀河") && xml.contains("夜"), "pending のテキスト保持: $xml")
        // seg1 のテキストは表より前（段落として退避）に出る。
        assertTrue(xml.indexOf("銀河") < xml.indexOf("<table"), "段落は表より前: $xml")
        assertTrue(xml.indexOf("夜") < xml.indexOf("<table"), "段落は表より前: $xml")
    }

    /** 前行の該当槽が空セルの欠落は §3.6 の空補間となる（テキスト保持 anchor の rowspan は §12.4 T3-3）。 */
    @Test
    fun padsMissingGridSlotWithEmptyCell() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 44))
        words.addAll(text("一"))
        words.addAll(cell(48, 84)) // 空（anchor にならない）
        words.add(WALL)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 44))
        words.addAll(text("三"))
        words.add(WALL) // グリッド槽 48 欠落 → 空セル補間（§3.6）

        val xml = parseXhtml(cfb(mapOf("/DocumentText" to stream(words))))
        assertContains(xml, "<tr><td>一</td><td></td></tr>")
        assertFalse(xml.contains("rowspan"), "空 anchor に rowspan を付けない: $xml")
        assertTrue(
            Regex("<tr><td>三</td><td></td></tr>|<tr><td>三</td><td/></tr>").containsMatchIn(xml),
            "欠落グリッド槽の空セル補間: $xml",
        )
    }

    @Test
    fun closesTableOnSectionTransitionThenTextBecomesParagraph() {
        // §3.7: 0x0020 遷移で table を閉じ、以降のテキストは段落へ
        val xml = parseXhtml(twoRowTableFixture())
        val tableEnd = xml.indexOf("</table>")
        val noteIndex = xml.indexOf("二、活版所")
        assertTrue(tableEnd in 0 until noteIndex, "遷移後のテキストは表の外に出る")
        assertContains(xml, "</tbody></table><p>二、活版所</p>")
    }

    @Test
    fun flushesTableAtEndOfStreamWithWellFormedXml() {
        // EOS flush: WALL/遷移なしで途切れても SAX を閉じ切る（Tika 4 pipes invariant §6）
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河帯"))
        words.addAll(cell(84, TABLE_RIGHT))
        words.addAll(text("けぶった"))

        val data = cfb(mapOf("/DocumentText" to stream(words)))
        val xml = parseXhtml(data)

        // SAX が閉じ切っていれば XML として well-formed（Tika 4 pipes で 422 を回避できる）
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        assertContains(xml, "<tr><td>銀河帯</td><td>けぶった</td></tr>")
        assertTrue(xml.contains("</table>") && xml.contains("</body></html>"), xml)
    }

    @Test
    fun structuresMultiSheetsWithSheetDivs() {
        val tableWords = mutableListOf<Int>()
        tableWords.addAll(rowHeader(n = 2))
        tableWords.addAll(cell(8, 80))
        tableWords.addAll(text("銀河"))
        tableWords.addAll(cell(84, TABLE_RIGHT))
        tableWords.addAll(text("夜"))
        tableWords.add(WALL)

        val data = cfb(
            mapOf(
                "/DocumentText" to stream(tableWords),
                "/ObjectSheets/DocSheet/DocItemInfo" to docItemInfo(listOf("カムパネルラ" to 1L)),
                "/ObjectSheets/DocSheet/DOCS_0001/DocumentText" to markerText("ジョバンニは窓をあけました。"),
            ),
        )

        val xml = parseXhtml(data)
        assertEquals(2, Regex("<div class=\"sheet\">").findAll(xml).count())
        assertContains(xml, "<div class=\"sheet\"><h2>タイトル</h2><table>")
        assertContains(xml, "<div class=\"sheet\"><h2>カムパネルラ</h2><p>ジョバンニは窓をあけました。</p></div>")
        assertEquals(
            "タイトル銀河夜カムパネルラジョバンニは窓をあけました。",
            textOnly(xml),
            "連結契約のテキスト全量保存",
        )
    }

    @Test
    fun wrapsLayoutBoxTextInDivKeepingNoteMarker() {
        val data = cfb(
            mapOf(
                "/DocumentText" to markerText("銀河鉄道の夜"),
                "/LayoutBoxText" to layoutBoxStream(listOf("大きな黒い星座の図")),
            ),
        )

        val xml = parseXhtml(data)
        assertContains(xml, "<div class=\"layout-box\"><p>大きな黒い星座の図</p></div>")
        assertContains(xml, "※枠内テキスト") // -t 互換のため注記は text として保持（§10.3-3 踏襲）
        assertEquals("銀河鉄道の夜※枠内テキスト大きな黒い星座の図", textOnly(xml))

        // -t プレーン契約: 本文＋枠テキストが欠落なく残る
        val body = parseBodyText(data).replace(Regex("\\s+"), "")
        assertContains(body, "銀河鉄道の夜")
        assertContains(body, "大きな黒い星座の図")
    }

    @Test
    fun plainDocumentKeepsFlatContractAndText() {
        // 記録なしの平文文書: 構造を作らずテキストを保持（salvage/互換ゲート）
        val data = cfb(mapOf("/DocumentText" to markerText("カムパネルラが手をあげました。\n")))
        val body = parseBodyText(data)
        assertContains(body, "カムパネルラが手をあげました。")

        val xml = parseXhtml(data)
        assertFalse(xml.contains("<table"))
        assertEquals("カムパネルラが手をあげました。", textOnly(xml))
    }
}
