package com.hiyowa.tika.jtd

import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.WALL
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.WRAP
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.cell
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.inline
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.markerText
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.numberingParagraph
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.paragraph
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.rawTextSegment
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.rowHeader
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.skippedRuby
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.stream
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.text
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.transition
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC 0013 流路パーサー（Phase 0 レコード解釈層）のイベント列テスト。
 *
 * 統制サンプル（§9.7/§9.8）のイベント周期を合成バイナリで再現し、
 * 流路イベント語彙（RowHeader / SpanDeclaration / RowAdvance / Wrap /
 * SectionTransition）とフッター検証フォールスルーを検証する。
 * 本文文言は『銀河鉄道の夜』から引用（AGENTS.md 準拠）。
 */
class RuleFlowParserTest {

    @Test
    fun stableGridTwoRowsProjectToFlowEvents() {
        val words = mutableListOf<Int>()
        words.addAll(paragraph(0x0026))
        words.addAll(text("一、午后の授業"))
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(0, 4))
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

        val events = RuleFlowParser.parse(stream(words))

        assertEquals(
            listOf(
                FlowEvent.ParagraphHeader(0x0026),
                FlowEvent.Text("一、午后の授業"),
                FlowEvent.RowHeader(0x008f, 2),
                FlowEvent.SpanDeclaration(0, 4),
                FlowEvent.SpanDeclaration(8, 80),
                FlowEvent.Text("銀河帯"),
                FlowEvent.SpanDeclaration(84, 160),
                FlowEvent.Text("けぶった"),
                FlowEvent.RowAdvance,
                FlowEvent.RowHeader(0x008f, 2),
                FlowEvent.SpanDeclaration(0, 4),
                FlowEvent.SpanDeclaration(8, 80),
                FlowEvent.Text("天上"),
                FlowEvent.SpanDeclaration(84, 160),
                FlowEvent.Text("白い"),
                FlowEvent.RowAdvance,
                FlowEvent.SectionTransition,
            ),
            events,
        )
    }

    @Test
    fun columnCountArithmeticFromW5() {
        // w5 = 4(n-1)+3 → n = 4（RFC 0013 §3.1）
        val events = RuleFlowParser.parse(stream(rowHeader(n = 4)))
        assertEquals(listOf(FlowEvent.RowHeader(0x008f, 4)), events)

        val degenerate = RuleFlowParser.parse(
            stream(
                mutableListOf<Int>().apply {
                    // w5 が算術 (w5-3) % 4 != 0 に合わない変種 → declaredSpanCount=null で通す
                    addAll(
                        RuleFlowFixtureBuilder.record(0x0010, listOf(0x0000, 0x008f, 0x0008, 160, 0x0022)),
                    )
                },
            ),
        )
        assertEquals(listOf(FlowEvent.RowHeader(0x008f, null)), degenerate)
    }

    @Test
    fun wallAndWrapEvents() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 1))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.add(WRAP)
        words.addAll(text("鉄道"))
        words.add(WALL)

        val events = RuleFlowParser.parse(stream(words))
        assertEquals(
            listOf(
                FlowEvent.RowHeader(0x008f, 1),
                FlowEvent.SpanDeclaration(8, 80),
                FlowEvent.Text("銀河"),
                FlowEvent.Wrap,
                FlowEvent.Text("鉄道"),
                FlowEvent.RowAdvance,
            ),
            events,
        )
    }

    @Test
    fun unvalidatedRecordMarkerProjectsNoStructuralEvents() {
        // 偽 0x001c（連番語列・フッター検証不通過）は表構造イベント化しない（RFC 0009 §誤検出）
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河帯"))
        words.add(WALL)
        val junkOpener = 0x001c
        val junkBody = (0x001d..0x002a).toList()
        words.add(junkOpener)
        words.addAll(junkBody)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(84, 160))
        words.addAll(text("天上"))
        words.add(WALL)

        val events = RuleFlowParser.parse(stream(words))
        val spans = events.filterIsInstance<FlowEvent.SpanDeclaration>()
        assertEquals(listOf(8 to 80, 84 to 160), spans.map { it.left to it.right })
        assertFalse(events.any { it is FlowEvent.SectionTransition })
    }

    @Test
    fun paragraphAndTransitionHeaderEvents() {
        val words = mutableListOf<Int>()
        words.addAll(paragraph(0x0020))
        words.addAll(text("銀河の夜"))
        words.addAll(transition())
        words.addAll(paragraph(0x0024))
        words.addAll(text("天上"))

        val events = RuleFlowParser.parse(stream(words))
        assertEquals(
            listOf(
                FlowEvent.ParagraphHeader(0x0020),
                FlowEvent.Text("銀河の夜"),
                FlowEvent.SectionTransition,
                FlowEvent.ParagraphHeader(0x0024),
                FlowEvent.Text("天上"),
            ),
            events,
        )
    }

    @Test
    fun numberingPrefixCarriedIntoFlowText() {
        val words = mutableListOf<Int>()
        words.addAll(numberingParagraph(style = 0x0002))
        words.addAll(text("銀河ステーション"))

        val events = RuleFlowParser.parse(stream(words))
        assertEquals(FlowEvent.ParagraphHeader(0x0026), events.first())
        val textEvents = events.filterIsInstance<FlowEvent.Text>()
        assertEquals(1, textEvents.size)
        assertEquals("(1) 銀河ステーション", textEvents.single().text)
    }

    @Test
    fun visibleInlineYieldsTextSkippedRubyYieldsNothing() {
        val words = mutableListOf<Int>()
        words.addAll(paragraph(0x0026))
        words.addAll(inline("午后"))
        words.addAll(skippedRuby("ごご"))
        words.addAll(text("の授業"))

        val events = RuleFlowParser.parse(stream(words))
        val concatenated = events.filterIsInstance<FlowEvent.Text>().joinToString("") { it.text }
        assertEquals("午后の授業", concatenated)
        assertTrue(events.none { e -> e is FlowEvent.Text && e.text == "ごご" })
    }

    @Test
    fun rawTextSegmentProjectsToSingleText() {
        val events = RuleFlowParser.parse(rawTextSegment("宮沢賢治"))
        assertEquals(listOf(FlowEvent.Text("宮沢賢治")), events)
    }

    @Test
    fun splitConcatenatedEmbeddedFragments() {
        // salvage 経路の [0,0] 連結 blob を断片毎に流路化できる（embeddedDocumentText bytes と同一レイアウト）
        val frag1 = markerText("銀河鉄道の夜")
        val frag2 = markerText("宮沢賢治")
        val blob = frag1 + byteArrayOf(0, 0) + frag2
        val events = RuleFlowParser.parse(blob)
        val text = events.filterIsInstance<FlowEvent.Text>().joinToString("") { it.text }
        assertContains(text, "銀河鉄道の夜")
        assertContains(text, "宮沢賢治")
    }

    @Test
    fun splitFragmentsWithSegmentHeaders() {
        // 先頭断片が TextV.01 長ヘッダを持つ blob（実 /DocumentText 断片と同型）で、
        // documentTextUnitLimit の打ち切りで後続断片が全滅しない（多重断片連結 blob 回帰:
        // 実測で payload.text 全量に対して flow が先頭断片数文字のみになる問題。
        // 修正後は magic 分割で断片毎に解析され全テキストが保存される）。
        val blob = rawTextSegment("銀河鉄道の夜") + byteArrayOf(0, 0) + rawTextSegment("宮沢賢治")
        val events = RuleFlowParser.parse(blob)
        val text = events.filterIsInstance<FlowEvent.Text>().joinToString("") { it.text }
        assertContains(text, "銀河鉄道の夜")
        assertContains(text, "宮沢賢治")
    }

    @Test
    fun flowTextPreservesAllVisiblePlainText() {
        // 流路テキストの連結は現行平文抽出（全可視文字・空白正規化）と一致しなければならない
        val words = mutableListOf<Int>()
        words.addAll(paragraph(0x0026))
        words.addAll(text("一、午后の授業"))
        words.add(WRAP)
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(0, 4))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河帯"))
        words.addAll(inline("午后"))
        words.add(WRAP)
        words.addAll(cell(84, 160))
        words.addAll(text("けぶった"))
        words.add(WALL)
        words.addAll(transition())
        words.addAll(numberingParagraph())
        words.addAll(text("銀河ステーション"))

        val data = stream(words)
        val flowText = RuleFlowParser.parse(data)
            .filterIsInstance<FlowEvent.Text>()
            .joinToString("") { it.text }
        val plainText = DocumentTextParser.extractDocumentText(data)

        val normalized = { s: String -> s.replace(Regex("\\s+"), "") }
        assertTrue(
            normalized(plainText).isNotEmpty(),
            "フィクスチャ平文が空（フィクスチャが不正）",
        )
        assertEquals(normalized(plainText), normalized(flowText))
    }

    /**
     * 本文 run の途中に混入したインライン規則領域（0xFE00–0xFE0F レコード系ノイズ）を
     * スキップし、テキスト run を領域跨ぎで連続復元する。
     * 領域内に裸の 0x001F / 0x000e / ゼロパディングが含まれてもイベント化しない。
     * 文言は『銀河鉄道の夜』から引用（AGENTS.md 準拠）。
     */
    @Test
    fun inlineRuleRegionIsSkippedAndTextRunContinuesAcrossIt() {
        val words = mutableListOf<Int>()
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(0, 4))
        words.addAll(cell(8, 80))
        words.addAll(text("カムパネルラが"))
        // FE0X 系インライン記録領域の実測形状模倣（裸 0x001f・0x000e・ゼロ埋めを含む）。
        words.addAll(
            listOf(
                0xFE03, 0x0200, 0x0214, 0x0480, 0x0000, 0x00FF, 0x0000, 0x0000, 0x0001,
                0xFE0F, 0x0400, 0x0000, 0x00FF, 0x000E, 0x0000, 0x0000, 0x0002,
                0xFE04, 0x01C8, 0x0501, 0xC8FF, 0x0000, 0x0000, 0x0001,
                0xFE04, 0x0100, 0x0501, 0x00FF, 0x0000, 0x0000, 0x0002,
                0x001F, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000, 0x0000,
            ),
        )
        words.addAll(text("天の川がしらしらと亘っている"))
        words.add(WALL)
        words.addAll(transition())

        val events = RuleFlowParser.parse(stream(words))
        val textEvents = events.filterIsInstance<FlowEvent.Text>()

        assertEquals(listOf("カムパネルラが天の川がしらしらと亘っている"), textEvents.map { it.text })
        // 末尾の正規 WALL 以外の行境界を行境界として漏らしてはならない
        // （領域内 0x000e は規則領域の一部として消費される）。
        assertEquals(1, events.filterIsInstance<FlowEvent.RowAdvance>().size)
    }

    /**
     * 平文パスでも同様にインライン規則領域をスキップしてテキストを連結する
     * （流路構造化パスとの整合）。
     */
    @Test
    fun extractDocumentTextResumesAcrossInlineRuleRegionWithoutGarbage() {
        val words = mutableListOf<Int>()
        words.add(0x001F)
        words.addAll(text("カムパネルラが"))
        words.addAll(
            listOf(
                0xFE03, 0x0200, 0x0214, 0x0480, 0x0000, 0x00FF, 0x0000, 0x0000, 0x0001,
                0xFE0F, 0x0400, 0x0000, 0x00FF, 0x000E, 0x0000, 0x0000, 0x0002,
                0x001F, 0x0000, 0x0000, 0x0000, 0x0000,
            ),
        )
        words.addAll(text("天の川がしらしらと亘っている"))

        val plain = DocumentTextParser.extractDocumentText(stream(words))
        assertEquals("カムパネルラが天の川がしらしらと亘っている", plain)
    }
}
