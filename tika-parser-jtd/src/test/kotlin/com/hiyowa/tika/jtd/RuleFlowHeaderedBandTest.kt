package com.hiyowa.tika.jtd

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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC 0013 追補 §17（登録案）: 行ヘッダ付き縦書き band と幻影空列・細分列番号表頭。
 *
 * 実版面パターン（時割選択様式）: 全 band 行が行ヘッダ（w4=0x008f）を伴い、
 * 本文は 1 マス 1 文字の縦流れ（WRAP-open）で流れる。集中行直前に本文空白の
 * WRAP-open 埋め立て行が入るため、§13.2 の継続判定（本文+WRAP 付与）だけでは
 * openContinued が殺されて集中行が別 `<tr>` へ分離していた（living 維持則で解消）。
 * 表頭は細分列番号行（数字 columns）で本文 band グリッドと左辺を共有しないため、
 * §15 の grid 照合では逸脱して段落へ転落していた（digitHeaderLike 構造化 Adopt で解消）。
 * また band 全行が細幅（≤8）の端余白マスを空白で宣言し続け、幻影空列として
 * 左右に現れていた（全行宣言＋細幅＋全空白の除去で解消。§3.6 補間契約の
 * Wide 空欄・非全行宣言は不発で温存される）。
 *
 * 本文文言は『銀河鉄道の夜』（青空文庫・パブリックドメイン）から引用（AGENTS.md 準拠）。
 */
class RuleFlowHeaderedBandTest {

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
     * 時割選択様式ミニフィクスチャ:
     * 細分列番号表頭（pending・行ヘッダ無し）→ 行ヘッダ付き band（宣言行→
     * 縦流れ→埋め立て白紙 WRAP-open→本文付き閉塞集中行）→ 全マス閉塞の空区切り行。
     * 左右に細幅（幅 6）の全行空白端列（幻影）を含む。
     */
    private fun headeredBandFixture(): ByteArray {
        val words = mutableListOf<Int>()
        words.addAll(paragraph(0x0026))
        words.addAll(text("一、午后の授業"))
        // 細分列番号表頭（行ヘッダ無し・pending・8 マス以上。左右に細幅幻影端列 [0,6]/[114,120]）
        words.addAll(cell(0, 6))
        words.addAll(cell(10, 48))
        words.addAll(text("銀河ステーション"))
        words.addAll(cell(52, 58))
        words.addAll(text("１"))
        words.addAll(cell(62, 68))
        words.addAll(text("２"))
        words.addAll(cell(72, 78))
        words.addAll(text("３"))
        words.addAll(cell(82, 88))
        words.addAll(text("４"))
        words.addAll(cell(92, 98))
        words.addAll(text("５"))
        words.addAll(cell(102, 108))
        words.addAll(text("６"))
        words.addAll(cell(114, 120))
        words.add(WALL)
        // band R1: 宣言行（全マス WRAP-open・本文付き）
        words.addAll(rowHeader(n = 5))
        words.addAll(cell(0, 6))
        words.add(WRAP)
        words.addAll(cell(10, 14))
        words.add(WRAP)
        words.addAll(cell(18, 48))
        words.addAll(text("銀河"))
        words.add(WRAP)
        words.addAll(cell(52, 108))
        words.add(WRAP)
        words.addAll(cell(114, 120))
        words.add(WRAP)
        words.add(WALL)
        // band R2: 縦流れ本文（WRAP-open 継続）
        words.addAll(rowHeader(n = 5))
        words.addAll(cell(10, 14))
        words.addAll(text("駅"))
        words.add(WRAP)
        words.addAll(cell(18, 48))
        words.addAll(text("天気輪"))
        words.add(WRAP)
        words.addAll(cell(52, 108))
        words.addAll(text("列車"))
        words.add(WRAP)
        words.add(WALL)
        // band R3: 埋め立て白紙（WRAP-open 継続・本文空白）→ 継続を殺さない
        words.addAll(rowHeader(n = 5))
        words.addAll(cell(10, 14))
        words.add(WRAP)
        words.addAll(cell(18, 48))
        words.add(WRAP)
        words.addAll(cell(52, 108))
        words.add(WRAP)
        words.add(WALL)
        // band R4: 集中行（実測準拠: 全列宣言＋本文は数字のみ・WRAP なし）
        words.addAll(rowHeader(n = 5))
        words.addAll(cell(0, 6))
        words.addAll(cell(10, 14))
        words.add(WRAP)
        words.addAll(cell(18, 48))
        words.add(WRAP)
        words.addAll(cell(52, 108))
        words.addAll(text("２"))
        words.addAll(cell(114, 120))
        words.add(WALL)
        // 空区切り行（全マス空白・WRAP なし）→ band 終端
        words.addAll(rowHeader(n = 5))
        words.addAll(cell(0, 6))
        words.addAll(cell(10, 14))
        words.addAll(cell(18, 48))
        words.addAll(cell(52, 108))
        words.addAll(cell(114, 120))
        words.add(WALL)
        words.addAll(transition())
        return cfb(mapOf("/DocumentText" to stream(words)))
    }

    @Test
    fun adoptsNumericHeaderAndKeepsHeaderedBandInOneRow() {
        val xml = parseFixture(headeredBandFixture())

        // tr 構成: 表頭 1 + band 可視 1（+ 段落）
        val trCount = xml.split("<tr>").size - 1
        assertEquals(2, trCount, "可視行数: $xml")

        // 細分列番号表頭が段落へ転落せず表頭行として Adopt される
        assertTrue(xml.contains("<table>"), "表が開かない: $xml")
        val headerRow = Regex("<tr>(.*?)</tr>").find(xml)!!.groupValues[1]
        assertHasHeaderCell(headerRow)

        // 表頭行は細幅幻影端列を持たない（[0,6]/[94,100] が落ちている）
        assertTrue(headerRow.startsWith("<td colspan=\"2\">銀河ステーション</td>"), "表頭行の先頭: $headerRow")

        // band: 縦流れ＋埋め立て白紙＋集中行が単一可視行へ。数字は同一マスへ連結
        val trs = Regex("<tr>(.*?)</tr>").findAll(xml).map { it.groupValues[1] }.toList()
        assertTrue(trs[1].contains("<td>駅</td>") || trs[1].contains("駅"), "年号レーン: ${trs[1]}")
        assertTrue(trs[1].contains("銀河<br/>天気輪"), "本文レーンの縦流れ: ${trs[1]}")
        assertTrue(trs[1].contains("列車<br/>２"), "集中数字が同一レーンへ連結: ${trs[1]}")
        // 保存則
        assertEquals(
            "一、午后の授業銀河ステーション１２３４５６駅銀河天気輪列車２",
            textOnly(xml),
            "テキスト多重集合: $xml",
        )
        // 埋め立て白紙行・空区切り行の幽霊行禁止
        assertFalse(Regex("<tr><td></td><td></td><td></td></tr>").containsMatchIn(xml), "幽霊行: $xml")
    }

    private fun assertHasHeaderCell(headerRow: String) {
        assertTrue(headerRow.contains("銀河ステーション"), "表頭に表冠ラベル: $headerRow")
        assertTrue(headerRow.contains(">１</td>") && headerRow.contains(">４</td>"), "細分列番号: $headerRow")
    }
}
