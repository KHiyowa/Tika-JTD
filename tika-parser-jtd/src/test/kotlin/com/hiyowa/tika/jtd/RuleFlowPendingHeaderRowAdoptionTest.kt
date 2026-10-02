package com.hiyowa.tika.jtd

import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.WALL
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
 * RFC 0013 追補 §15（登録案）: 表 open 時の pending multi-span 行取り込み。
 *
 * 実版面パターン: 罫線表の直前に、行ヘッダ（w4=0x008f）を伴わない multi-span
 * ヘッダ行が現れる（表題段落 → ヘッダ行 → 本文行…）。現行状態機械では
 * - ヘッダ行は安定グリッド未確認のため pending に退避され（finalizeSegment の else 分岐）、
 * - 本文行が `w4=0x008f` 行ヘッダ補強で表を開くとき、pending は
 *   abandonPendingToParagraph で段落へ放棄される。
 * その結果、ヘッダ行が `<tr>` ではなく `<p>` 連結テキスト（スパン区切り欠落）になる。
 *
 * 契約（当該行は同一安定グリッドの表頭部である）:
 * - pending 行の left 座標列が表 open 行と一致、**または一方が他方の部分集合
 *   （列の細分化 refinement）**の場合、pending を表先頭行として取り込む。
 *   実文書では点線（うす罫）相当の列が本文行側だけで細分されるため、
 *   ヘッダ行 left 座標列 ⊊ 本文行 left 座標列 が頻出する。
 * - ヘッダ行テキストは連結段落ではなく `<tr><td>…</td></tr>` として出力する。
 * - 安定グリッド不一致（左辺不同）の pending は従来どおり段落へ退避する（保存則 §2.2）。
 *
 * 本文文言は『銀河鉄道の夜』から引用（AGENTS.md 準拠）。
 */
class RuleFlowPendingHeaderRowAdoptionTest {

    private fun compact(xml: String): String =
        xml
            .replace(Regex("<br\\s*/>\\s*"), "<br/>")
            .replace(Regex(">\\s+<"), "><")
            .trim()

    private fun textOnly(xml: String): String =
        xml.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), "")

    /**
     * 表題段落 → ヘッダ行（行ヘッダ無し multi-span）→ 本文行（行ヘッダ付き）→ 終了。
     * グリッド左辺はヘッダ行・本文行で同一（8, 84）。
     */
    private fun pendingHeaderRowFixture(): ByteArray {
        val words = mutableListOf<Int>()
        words.addAll(paragraph(0x0026))
        words.addAll(text("一、午后の授業"))
        // ヘッダ行: 行ヘッダ無し・multi-span（pending に退避される側）
        words.addAll(paragraph(0x0026))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.addAll(cell(84, 160))
        words.addAll(text("ステーション"))
        words.add(WALL)
        // 本文行: w4=0x008f 行ヘッダで表が開く側
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("カムパネルラ"))
        words.addAll(cell(84, 160))
        words.addAll(text("夜"))
        words.add(WALL)
        words.addAll(transition())
        return cfb(mapOf("/DocumentText" to stream(words)))
    }

    /**
     * 実文書フィンガープリント: ヘッダ行は列細分化（refinement）を受けない span 列。
     * ヘッダ行 span 左辺 [8, 84] ⊂ 本文行 span 左辺 [8, 84, 124]（ヘッダの
     * 「ステーション」は本文行の 2 スパン [84,120]+[124,160] にまたがる表頭セル）。
     */
    private fun refiningHeaderRowFixture(): ByteArray {
        val words = mutableListOf<Int>()
        words.addAll(paragraph(0x0026))
        words.addAll(text("一、午后の授業"))
        // ヘッダ行: 細分化前の 2 span（[84,160] が後の 2 列を跨ぐ）
        words.addAll(paragraph(0x0026))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.addAll(cell(84, 160))
        words.addAll(text("ステーション"))
        words.add(WALL)
        // 本文行: 3 span（細分化後のグリッド）
        words.addAll(rowHeader(n = 3))
        words.addAll(cell(8, 80))
        words.addAll(text("カムパネルラ"))
        words.addAll(cell(84, 120))
        words.addAll(text("夜"))
        words.addAll(cell(124, 160))
        words.addAll(text("しずか"))
        words.add(WALL)
        words.addAll(transition())
        return cfb(mapOf("/DocumentText" to stream(words)))
    }

    @Test
    fun adoptsRefiningPendingHeaderRowIntoTableHead() {
        val handler = ToXMLContentHandler()
        TikaInputStream.get(ByteArrayInputStream(refiningHeaderRowFixture())).use { stream ->
            JtdParser().parse(stream, handler, Metadata(), ParseContext())
        }
        val xml = compact(handler.toString())

        // ヘッダ行は先頭 <tr>（細分される列は colspan 近似で跨ぐ）
        assertContains(xml, "<tr><td>銀河</td>")
        assertContains(xml, "<td>カムパネルラ</td>")
        assertTrue(xml.indexOf("銀河") < xml.indexOf("カムパネルラ"), "表頭が先出順: $xml")
        assertFalse(xml.contains("<p>銀河ステーション</p>"), "pending ヘッダ行の段落放棄: $xml")
        // テキスト全量保存則
        assertEquals("一、午后の授業銀河ステーションカムパネルラ夜しずか", textOnly(xml))
    }

    /**
     * 実文書フィンガープリント③: ヘッダ行の直後に空区切りセグメント（WALL 重複）が
     * 挟まる版面。現行は content.isEmpty() 分岐の abandonPendingToParagraph で
     * pending ヘッダ行を段落へ放棄し、後続の行ヘッダ表と接続できない。
     */
    private fun blankDividerHeaderRowFixture(): ByteArray {
        val words = mutableListOf<Int>()
        words.addAll(paragraph(0x0026))
        words.addAll(text("一、午后の授業"))
        // ヘッダ行: multi-span・行ヘッダ無し → pending
        words.addAll(paragraph(0x0026))
        words.addAll(cell(8, 80))
        words.addAll(text("銀河"))
        words.addAll(cell(84, 160))
        words.addAll(text("ステーション"))
        words.add(WALL)
        // 空区切りセグメント（WALL 重複）
        words.add(WALL)
        // 本文行: 行ヘッダ付き
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(8, 80))
        words.addAll(text("カムパネルラ"))
        words.addAll(cell(84, 160))
        words.addAll(text("夜"))
        words.add(WALL)
        words.addAll(transition())
        return cfb(mapOf("/DocumentText" to stream(words)))
    }

    @Test
    fun adoptsPendingHeaderRowAcrossBlankDivider() {
        val handler = ToXMLContentHandler()
        TikaInputStream.get(ByteArrayInputStream(blankDividerHeaderRowFixture())).use { stream ->
            JtdParser().parse(stream, handler, Metadata(), ParseContext())
        }
        val xml = compact(handler.toString())

        assertContains(xml, "<tr><td>銀河</td><td>ステーション</td></tr>")
        assertFalse(xml.contains("<p>銀河ステーション</p>"), "空区切り行での pending 放棄: $xml")
        assertEquals("一、午后の授業銀河ステーションカムパネルラ夜", textOnly(xml))
    }

    @Test
    fun adoptsPendingHeaderRowIntoTableHead() {
        val handler = ToXMLContentHandler()
        TikaInputStream.get(ByteArrayInputStream(pendingHeaderRowFixture())).use { stream ->
            JtdParser().parse(stream, handler, Metadata(), ParseContext())
        }
        val xml = compact(handler.toString())

        // ヘッダ行は表の先頭 <tr> として構造に出る
        assertContains(xml, "<tr><td>銀河</td><td>ステーション</td></tr>")
        assertContains(xml, "<tr><td>カムパネルラ</td><td>夜</td></tr>")
        assertEquals(2, Regex("<tr>").findAll(xml).count(), "データ行は 2 行のみ: $xml")
        // pending 連結による段落化が出てはならない
        assertFalse(xml.contains("<p>銀河ステーション</p>"), "pending ヘッダ行の段落放棄: $xml")
        // テキスト全量保存則
        assertEquals("一、午后の授業銀河ステーションカムパネルラ夜", textOnly(xml))
    }
}
