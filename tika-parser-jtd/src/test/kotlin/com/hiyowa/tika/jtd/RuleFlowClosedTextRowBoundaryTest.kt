package com.hiyowa.tika.jtd

import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.WALL
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.WRAP
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.cell
import com.hiyowa.tika.jtd.RuleFlowFixtureBuilder.cfb
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RFC 0013 追補 §19（登録案）: 語6 閉塞系（bit0）本文マスの進行を行境界とする。
 *
 * 実測（監修様式・同一グリッド連枠）では科目名マスの語6=0x0003（完結細身）が
 * 毎行進行し、マークマス（0x0001）は列位置が行毎にずれて空欄対となるため
 * §14 mergeConflict（語6==0x0001 厳密一致＋両側本文）が発火せず、複数実行が
 * 1 論理行へ過剰統合されていた。
 *
 * 判別: 標準マス（0x00ff）を除き、語6 bit0 の立つ閉塞系マス（0x0001/0x0003）の
 * 新本文が、同一 span の閉塞（WRAP-open 非）済本文マスへ進行したら行境界。
 * 標準マス除外により全マス 0x00ff の時割選択様式（§17）は不発で従来動作を維持、
 * ラベルレーン（WRAP-open）は生き延びて縦流れ連結を保つ。
 *
 * 本文文言は『銀河鉄道の夜』（青空文庫・パブリックドメイン）から引用（AGENTS.md 準拠）。
 */
class RuleFlowClosedTextRowBoundaryTest {

    private fun compact(xml: String): String =
        xml
            .replace(Regex("<br\\s*/>\\s*"), "<br/>")
            .replace(Regex(">\\s+<"), "><")
            .trim()

    private fun textOnly(xml: String): String =
        xml.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), "")

    private fun parseFixture(fixture: ByteArray): String {
        val handler = ToXMLContentHandler()
        TikaInputStream.get(ByteArrayInputStream(fixture)).use { s ->
            JtdParser().parse(s, handler, Metadata(), ParseContext())
        }
        return compact(handler.toString())
    }

    /**
     * stableGrid 3 実行（span 署名不変）: ラベル 2 列は WRAP-open 縦流れ、
     * 科目名マス（語6=0x0003 閉塞系）が毎行新本文で進行。マーク(0x0001) は
     * 実行1 のみで以降は空（対空進行につき mergeConflict 旧厳密一致は不発）。
     */
    private fun closedSubjectRowsFixture(): ByteArray {
        val words = mutableListOf<Int>()
        // R1: 科目「天気輪」＋マーク 1 個目
        words.addAll(rowHeader(n = 5))
        words.addAll(cell(14, 42, flags = 0x00ff))
        words.addAll(text("銀河"))
        words.add(WRAP)
        words.addAll(cell(44, 54, flags = 0x00ff))
        words.addAll(text("天"))
        words.add(WRAP)
        words.addAll(cell(56, 94, flags = 0x0003))
        words.addAll(text("天気輪"))
        words.addAll(cell(96, 106, flags = 0x0001))
        words.addAll(text("1"))
        words.addAll(cell(108, 118, flags = 0x0001))
        words.add(WALL)
        // R2: 科目「天上」進行（マーク空）→ 科目進行で行境界
        words.addAll(rowHeader(n = 5))
        words.addAll(cell(14, 42, flags = 0x00ff))
        words.addAll(text("銀河"))
        words.add(WRAP)
        words.addAll(cell(44, 54, flags = 0x00ff))
        words.addAll(text("気"))
        words.add(WRAP)
        words.addAll(cell(56, 94, flags = 0x0003))
        words.addAll(text("天上"))
        words.addAll(cell(96, 106, flags = 0x0001))
        words.addAll(cell(108, 118, flags = 0x0001))
        words.add(WALL)
        // R3: 科目「流星」進行（マーク空）→ 行境界。
        words.addAll(rowHeader(n = 5))
        words.addAll(cell(14, 42, flags = 0x00ff))
        words.addAll(text("銀河"))
        words.add(WRAP)
        words.addAll(cell(44, 54, flags = 0x00ff))
        words.addAll(text("野外"))
        words.add(WRAP)
        words.addAll(cell(56, 94, flags = 0x0003))
        words.addAll(text("流星"))
        words.addAll(cell(96, 106, flags = 0x0001))
        words.addAll(cell(108, 118, flags = 0x0001))
        words.add(WALL)
        words.addAll(transition())
        return cfb(mapOf("/DocumentText" to stream(words)))
    }

    @Test
    fun closedSubjectAdvanceBreaksRowInStableGrid() {
        val xml = parseFixture(closedSubjectRowsFixture())
        val trs = Regex("<tr>(.*?)</tr>").findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(3, trs.size, "科目進行で行境界（過剰統合検出）: $xml")
        assertTrue(trs[0].contains("天気輪"), "実行1: ${trs[0]}")
        assertTrue(trs[1].contains("天上"), "実行2: ${trs[1]}")
        assertTrue(trs[2].contains("流星"), "実行3: ${trs[2]}")
        // 保存則（全文字 DROP ゼロ・行順）
        assertEquals("銀河天天気輪1銀河気天上銀河野外流星", textOnly(xml), "保存則全文字: $xml")
    }
}
