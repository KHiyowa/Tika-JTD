package com.hiyowa.tika.jtd

import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.ToMarkdownContentHandler
import org.apache.tika.sax.ToXMLContentHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §12.5 回帰評価セット（オプトインゲート常設自動化）。
 *
 * `gradle test -Pflowstruct.rail=true` で有効化（`-Pflowstruct.root=<repo>` 併用）。
 * デフォルトはスキップ（実コーパスは git 未追跡・ ambientali 配置の前提）。
 *
 * 所有者再現 ground truth HTML を**構造骨格フィンガープリント**（tr=9・th=64・td=64・
 * colspan=54・rowspan=17・br=179・table=1）で発見し、同名 .jtd を現行パーサーに通して
 * §12.4 の受入基準を骨格数値で自動照合する。本文・ファイル名はコードにも stdout に也
 * 載せない（AGENTS.md §2 準拠・§9.13 登記トライブ参照）。
 */
class CorpusGroundTruthRailTest {

    private val corpusRoot: File?
        get() {
            if (!System.getProperty("flowstruct.rail", "false").toBoolean()) return null
            val root = System.getProperty("flowstruct.root") ?: return null
            val dir = File(root, "testdata/corpus").canonicalFile
            return if (dir.isDirectory) dir else null
        }

    private fun count(s: String, re: String) = Regex(re).findAll(s).count()

    /**
     * 表セル（th/td）ごとの `<br>` 本数をドキュメント順の列で返す（§21 oracle-derived invariant）。
     * 対象は平坦表（§9.13 fingerprint の table=1 前提）であり非貪欲マッチで対応する。
     */
    private fun brPerCell(html: String): List<Int> =
        Regex("(?is)<t[dh]\\b[^>]*>(.*?)</t[dh]>")
            .findAll(html)
            .map { seg -> Regex("<br\\b").findAll(seg.value).count() }
            .toList()

    private fun parseToXml(data: ByteArray): String {
        val handler = ToXMLContentHandler()
        TikaInputStream.get(ByteArrayInputStream(data)).use { s ->
            JtdParser().parse(s, handler, Metadata(), ParseContext())
        }
        return handler.toString()
    }

    private fun parseToMarkdown(data: ByteArray): String {
        val handler = ToMarkdownContentHandler()
        TikaInputStream.get(ByteArrayInputStream(data)).use { s ->
            JtdParser().parse(s, handler, Metadata(), ParseContext())
        }
        return handler.toString()
    }

    /** 正解 HTML の骨格フィンガープリント（§9.13 登記値）。 */
    private fun matchesGroundTruth(text: String): Boolean =
        count(text, "<tr\\b") == 9 &&
            count(text, "<th\\b") == 64 &&
            count(text, "<td\\b") == 64 &&
            count(text, "colspan=") == 54 &&
            count(text, "rowspan=") == 17 &&
            count(text, "<br\\b") == 179 &&
            count(text, "<table\\b") == 1

    private fun findGroundTruthPair(root: File): Pair<File, File>? {
        for (html in root.walkTopDown().filter { it.isFile && it.extension == "html" }) {
            if (matchesGroundTruth(html.readText(Charsets.UTF_8))) {
                val jtd = File(html.parentFile, html.nameWithoutExtension + ".jtd")
                if (jtd.isFile) return html to jtd
            }
        }
        return null
    }

    private fun skeleton(xml: String): Map<String, Int> {
        val c = xml.replace(Regex(">\\s+<"), "><")
        return mapOf(
            "table" to count(c, "<table\\b"),
            "tr" to count(c, "<tr\\b"),
            "td" to count(c, "<td\\b"),
            "colspan" to count(c, "colspan=\"[2-9]|colspan=\"[1-9]\\d"),
            "rowspan" to count(c, "rowspan=\"[2-9]|rowspan=\"[1-9]\\d"),
            "br" to count(c, "<br\\b"),
            "br-in-td" to count(c, "<td[^>]*>(?:(?!</td>).)*?<br"),
        )
    }

    /**
     * §12.4 受入基準の骨格照合（数値のみ・本文非開示）:
     * - T3-1: セル内 `<br>` は §13.2/§14.6/§15.4 登記済み継ぎ目に限定（総数凍結は §21 で撤廃、
     *   強比較 `inCellBrVectorIdentityVsOracle` へ移行）。Markdown 断片シグナルは実測 5 で回帰固定。
     * - T3-2: 表数 1〜2（18 分断の解消）
     * - T3-3: rowspan 属性出現（構造再現の方向性・全量一致は §7.2 追跡項）
     * - 参照トライブ: 正解側 table=1・tr=9・td=128・colspan=54・rowspan=17・br=179（§9.13）
     */
    @Test
    fun railAgainstGroundTruthSkeleton() {
        val root = corpusRoot ?: run { println("RAIL: disabled (skipped)"); return }
        val (htmlFile, jtdFile) = findGroundTruthPair(root)
            ?: run { println("RAIL: ground truth fingerprint not found"); return }

        val gtHtml = htmlFile.readText(Charsets.UTF_8)
        println("RAIL ground-truth: ${skeleton(gtHtml)}")

        val data = jtdFile.readBytes()

        val xml = parseToXml(data)
        DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))

        val out = skeleton(xml)
        println("RAIL output: $out")

        // 断片シグナル（末尾2空白で終端するパイプ行）: oracle は HTML のみなので実測値で回帰固定。
        // GFM 平坦化の既知制約は §5.2・§7.2 追跡項。登記来歴は §21（§16 後の再測定・2026-10-02 実測 5）。
        val md = parseToMarkdown(data)
        val brokenPipeRows = Regex("(?m)^\\|.*  $").findAll(md).count()
        assertTrue(brokenPipeRows <= 5, "T3-1: 登記継ぎ目超過のパイプ行分行断片=$brokenPipeRows")
        // 継ぎ目の同一性比較（§21）は別テスト `inCellBrVectorIdentityVsOracle` に分離。

        // T3-2: 表 1〜2 個
        assertTrue(out["table"]!! in 1..2, "T3-2: 表数=${out["table"]}（18 分断への退行禁止）")

        // T3-3: rowspan 出現・colspan 再現
        assertTrue(out["rowspan"]!! > 0, "T3-3: rowspan ゼロ（縦結合構造の欠落）")
        assertTrue(out["colspan"]!! > 0, "colspan ゼロへの退行禁止")

        // §12.5 準拠: 正解 HTML との照合は tr/td/th/colspan/rowspan 骨格のみ（本文は問わない）。
        // テキスト全量保存則は golden レール（gatagate.corpus）と合成契約テストが担保する。
        println("RAIL PASS: tables=${out["table"]} tr=${out["tr"]} rowspan=${out["rowspan"]} colspan=${out["colspan"]}")
    }

    /**
     * §21 oracle-derived invariant: 正解 HTML のセル単位 `<br>` 個数列（ドキュメント順）が
     * parser 出力と完全一致することを要求する。凍結総数（v1=0 → 2026-09-30=6）は §21 で撤廃済みで、
     * ここの一致が T3-1 の現在契約。差分は構造聖別（行・colspan/rowspan・個数の整数のみ・本文非開示）で
     * 登記可能にする。2026-10-02 時点の既知差分（追跡項 O2・§21）: 128 セル中 122 一致、
     * tall/wide セル 6 箇所が ±1（縦継続継ぎ目の判定差・§13.2/§14.2 領域）。
     */
    @Test
    fun inCellBrVectorIdentityVsOracle() {
        val root = corpusRoot ?: run { println("RAIL: disabled (skipped)"); return }
        val (htmlFile, jtdFile) = findGroundTruthPair(root)
            ?: run { println("RAIL: ground truth fingerprint not found"); return }

        val gtHtml = htmlFile.readText(Charsets.UTF_8)
        val xml = parseToXml(jtdFile.readBytes())

        val gtCells = brPerCell(gtHtml)
        val outCells = brPerCell(xml)
        assertEquals(gtCells.size, outCells.size, "T3-1 (§21): cell count mismatch vs oracle")

        // 差分セルの構造聖別（整数・属性のみ・本文非開示）。
        val rowSizes = { html: String ->
            Regex("(?is)<tr\\b[^>]*>(.*?)</tr>").findAll(html).map { r -> Regex("<t[dh]\\b").findAll(r.value).count() }.toList()
        }
        println("rows gt=${rowSizes(gtHtml)} out=${rowSizes(xml)}")
        val diffIdx = gtCells.indices.filter { gtCells[it] != outCells[it] }
        fun attrs(html: String, i: Int): String {
            val tags = Regex("(?is)<t[dh]\\b[^>]*>").findAll(html).toList()
            return Regex("(colspan|rowspan)=\"\\d+\"").findAll(tags[i].value).joinToString(",") { m -> m.value }
        }
        for (i in diffIdx) {
            println("DIFF idx=$i gt=${gtCells[i]} out=${outCells[i]} gtAttr=[${attrs(gtHtml, i)}] outAttr=[${attrs(xml, i)}]")
        }
        assertEquals(gtCells, outCells, "T3-1 (§21): per-cell br vector mismatch (seam position drift)")
        println("T3-1 oracle-identity: cells=${outCells.size} br-in-cells=${outCells.sum()}")
    }
}
