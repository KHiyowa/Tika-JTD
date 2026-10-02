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
 * RFC 0013 追補 §16v2（登録案）: 縦書き band の SPAN 木グリッド再構成。
 *
 * 実版面パターン（時間割様式）: 行ヘッダ（w4=0x008f）を伴わない WRAP-open 行が
 * 連続する band では、物理 WALL 行が本文の縦流れ（1 マス 1 文字）で��ヘダ数より
 * 多く、1 物理行 = 1 `<tr>` の既定射印では可視行数が膨張する。かといって
 * 同 span 署名の pairwise merge では band 先頭行に未宣言の tall 列（例: 年次ラベル）
 * を復元できず、本文を失う（保存則 §2.2 後退）。
 *
 * 契約（開→本文→閉→欠落→再開 境界モデル）:
 * - band 行（行ヘッダ無し・WRAP-open マス含む）は flush せず band バッファへ預ける。
 * - 可視行境界は 2 種:
 *   (a) 再開境界: ある span が closed（WRAP 無し）＋欠落のうえ本文を伴って再出現。
 *       直後の即再開（欠落 0 行）はセル内折返しであり境界にしない。
 *   (b) 細分境界: closed＋欠落の親 span の内側で new span が本文を伴って出現。
 * - band 終端（空行・行ヘッダ行・表 commit）で可視行列を復元し、
 *   同一可視行内の縦流れは継ぎ手連結（数字→半角空白、単文字→直結、複数文字→`<br/>`）
 *   で 1 セルへ合成する。tall 列は buildTable の欠落槽 rowspan 復元に委ねる。
 * - 空区切り行（全マス空白）は `<tr>` を立てない（幽霊行禁止・§16.2）。
 *
 * 本文文言は『銀河鉄道の夜』（青空文庫・パブリックドメイン）から引用（AGENTS.md 準拠）。
 */
class RuleFlowVisualGridTest {

    private fun compact(xml: String): String =
        xml
            .replace(Regex("<br\\s*/>\\s*"), "<br/>")
            .replace(Regex(">\\s+<"), "><")
            .trim()

    private fun textOnly(xml: String): String =
        xml.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), "")

    private fun assertHas(xml: String, needle: String, message: String) {
        assertTrue(xml.contains(needle), "$message -> $xml")
    }

    /**
     * 縦書き band ミニフィクスチャ（可視行 3 見本）:
     * 表頭（行ヘッダ）→ band 宣言行（全マス WRAP-open）→ 縦流れ →
     * 狭幅レーンの閉塞 → 細分（境界 b）→ 原幅再開＋単位数（境界 a）→ 空区切り → 終了。
     */
    private fun verticalBandFixture(): ByteArray {
        val words = mutableListOf<Int>()
        words.addAll(paragraph(0x0026))
        words.addAll(text("一、午后の授業"))
        // 表頭（w4=0x008f 行ヘッダで表 open）
        words.addAll(rowHeader(n = 2))
        words.addAll(cell(4, 8))
        words.addAll(text("1"))
        words.addAll(cell(12, 160))
        words.addAll(text("銀河ステーション"))
        words.add(WALL)
        // band R1: 全マス WRAP-open 宣言（本文なし＝マスを下へ開く）
        words.addAll(paragraph(0x0026))
        words.addAll(cell(4, 8))
        words.add(WRAP)
        words.addAll(cell(12, 40))
        words.add(WRAP)
        words.addAll(cell(48, 80))
        words.add(WRAP)
        words.addAll(cell(84, 160))
        words.addAll(text("銀河"))
        words.add(WRAP)
        words.add(WALL)
        // band R2: 縦流れ本文（WRAP-open 継続）
        words.addAll(paragraph(0x0026))
        words.addAll(cell(4, 8))
        words.addAll(text("銀河"))
        words.add(WRAP)
        words.addAll(cell(12, 40))
        words.addAll(text("カムパネルラ"))
        words.add(WRAP)
        words.addAll(cell(48, 80))
        words.addAll(text("天"))
        words.add(WRAP)
        words.addAll(cell(84, 160))
        words.add(WRAP)
        words.add(WALL)
        // band R3: 狭幅レーン [84,160] が本文付きで閉じる（WRAP なし）
        words.addAll(paragraph(0x0026))
        words.addAll(cell(4, 8))
        words.add(WRAP)
        words.addAll(cell(12, 40))
        words.add(WRAP)
        words.addAll(cell(48, 80))
        words.addAll(text("気"))
        words.add(WRAP)
        words.addAll(cell(84, 160))
        words.addAll(text("印刷"))
        words.add(WALL)
        // band R4: 細分境界 b（閉塞＋欠落の親 [84,160] 内へ new span が本文付きで出現）
        // band は毎行全スパン宣言（実測準拠: 縦流れ継続マスを空白 WRAP で開き続ける）
        words.addAll(paragraph(0x0026))
        words.addAll(cell(4, 8))
        words.add(WRAP)
        words.addAll(cell(12, 40))
        words.add(WRAP)
        words.addAll(cell(48, 80))
        words.add(WRAP)
        words.addAll(cell(84, 120))
        words.addAll(text("カムパネルラ"))
        words.add(WRAP)
        words.addAll(cell(124, 160))
        words.addAll(text("夜"))
        words.add(WRAP)
        words.add(WALL)
        // band R5: 再開境界 a（[84,160] が 1 行欠落のうえ本文「2」で再出現）＋ tall レン集計
        words.addAll(paragraph(0x0026))
        words.addAll(cell(4, 8))
        words.add(WRAP)
        words.addAll(cell(12, 40))
        words.addAll(text("3"))
        words.addAll(cell(48, 80))
        words.addAll(text("輪"))
        words.add(WRAP)
        words.addAll(cell(84, 160))
        words.addAll(text("2"))
        words.add(WALL)
        // 空区切り行（全マス空白・WRAP なし）→ band 終端、<tr> を立てない
        words.addAll(paragraph(0x0026))
        words.addAll(cell(4, 8))
        words.addAll(cell(12, 40))
        words.addAll(cell(48, 80))
        words.addAll(cell(84, 160))
        words.add(WALL)
        words.addAll(transition())
        return cfb(mapOf("/DocumentText" to stream(words)))
    }

    private fun parseFixture(fixture: ByteArray): String {
        val handler = ToXMLContentHandler()
        TikaInputStream.get(ByteArrayInputStream(fixture)).use { stream ->
            JtdParser().parse(stream, handler, Metadata(), ParseContext())
        }
        return compact(handler.toString())
    }

    @Test
    fun reconstructsBandIntoVisualRowsWithTallRowspans() {
        val xml = parseFixture(verticalBandFixture())

        // 可視行数: 表頭 1 + band 可視 3 = 4（band 5 物理行 + 空区切り 1 は消える）
        val trCount = xml.split("<tr>").size - 1
        assertEquals(4, trCount, "可視行数")

        // v0: tall 列は rowspan=3 で欠落槽から自動復元、閉塞済みの狭幅レーンは独立マス
        assertHas(xml, "<td colspan=\"4\">銀河ステーション</td>", "表頭行")
        assertHas(xml, "<tr><td>1</td>", "表頭先頭番号")
        assertHas(xml, "<td rowspan=\"3\">銀河</td><td rowspan=\"3\">カムパネルラ 3</td>", "tall レーン")
        assertHas(xml, "<td rowspan=\"3\">天気輪</td>", "WRAP-open 継続の tall レーン")
        assertHas(xml, "<td colspan=\"2\">銀河<br/>印刷</td>", "閉塞レーンの v0 セル")

        // v1: 細分レーン（セル内折返し連続と区別され別可視行になる）
        assertHas(xml, "<tr><td>カムパネルラ</td><td>夜</td></tr>", "細分 v1 行")

        // v2: 原幅再開＋単位数（colspan=2）
        assertHas(xml, "<tr><td colspan=\"2\">2</td></tr>", "再開 v2 行")
    }

    @Test
    fun keepsBandTextConservationAndNoGhostRows() {
        val xml = parseFixture(verticalBandFixture())

        // 保存則: band 内全本文が欠落なく 1 回ずつ出現（数字は tall セルへ集約）
        assertEquals(
            "一、午后の授業1銀河ステーション銀河カムパネルラ3天気輪銀河印刷カムパネルラ夜2",
            textOnly(xml),
            "テキスト多重集合: $xml",
        )
        // 幽霊行・段落退避の禁止
        assertFalse(Regex("<tr><td></td>.*?</tr>\\s*<td colspan=\"2\">2</td>").containsMatchIn(xml), "幽霊行")
        assertFalse(xml.contains("<p>カムパネルラ"), "band 本文の段落退避")
        assertFalse(xml.contains("<p>印刷"), "band 本文の段落退避")
        assertTrue(xml.contains("<p>一、午后の授業</p>"), "表題段落")
    }
}
