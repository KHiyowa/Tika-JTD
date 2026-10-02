package com.hiyowa.tika.jtd

import java.io.ByteArrayInputStream
import java.io.File
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.ToXMLContentHandler
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * 縦継続セル文書（月历グリッド型・.calendar tall cell トライブ）の ground truth 骨格レール（オプトイン）。
 *
 * `gradle test -Ptallrow.rail=true` で有効化（`-Ptallrow.root=<repo>` 併用）。
 *
 * §9.13 型トライブと同一手順で、所有者再現 ground truth HTML を骨格フィンガープリント
 * （tr=5・th=6・td=48・colspan=6・rowspan=0・br=20・table=1・br-in-td=20）で発見し、
 * 同名 .jtd を現行パーサーに通す。正解はセル内 `<br/>` を保持する縦統合形であり、
 * §12.4 T3-1（br-in-td=0 決定トライブ・tr=9 型）とは別トライブとして同居する
 * （RFC 0013 追補 §13: セル内 WRAP 平坦化 [Break] と縦継続統合 [LineBreak] の二語彙分離）。
 *
 * 本文・ファイル名はコードにも stdout にも載せない（AGENTS.md §2 準拠）。
 */
class CorpusTallRowGroundTruthRailTest {

    private val corpusRoot: File?
        get() {
            if (!System.getProperty("tallrow.rail", "false").toBoolean()) return null
            val root = System.getProperty("tallrow.root") ?: return null
            val dir = File(root, "testdata/corpus").canonicalFile
            return if (dir.isDirectory) dir else null
        }

    private fun count(s: String, re: String) = Regex(re).findAll(s).count()

    /** 正解 HTML の骨格フィンガープリント（§13 登記値・数値のみ）。 */
    private fun matchesGroundTruth(text: String): Boolean {
        val c = text.replace(Regex(">\\s+<"), "><")
        return count(c, "<tr\\b") == 5 &&
            count(c, "<th\\b") == 6 &&
            count(c, "<td\\b") == 48 &&
            count(c, "colspan=") == 6 &&
            count(c, "rowspan=") == 0 &&
            count(c, "<br\\b") == 20 &&
            count(c, "<table\\b") == 1
    }

    private fun findGroundTruthPair(root: File): Pair<File, File>? {
        for (html in root.walkTopDown().filter { it.isFile && it.extension == "html" }) {
            if (matchesGroundTruth(html.readText(Charsets.UTF_8))) {
                val jtd = File(html.parentFile, html.nameWithoutExtension + ".jtd")
                if (jtd.isFile) return html to jtd
            }
        }
        return null
    }

    /** テーブル直前の表外テキスト段落が復元されていること（§13.1 前置き保存）。 */
    @Test
    fun tallRowRailPrologueAndMergedRows() {
        val root = corpusRoot ?: run { println("TALLROW RAIL: disabled (skipped)"); return }
        val (htmlFile, jtdFile) = findGroundTruthPair(root)
            ?: run { println("TALLROW RAIL: ground truth fingerprint not found"); return }
        println("TALLROW RAIL fingerprint: ok")

        val data = jtdFile.readBytes()

        // 流路構造: 先頭ブロックは非空白テキスト段落（表外前置き）でなければならない。
        val payload = DocumentTextParser.readDocumentTextPayload(data).bytes
        val blocks = RuleFlowAssembler.assemble(RuleFlowParser.parse(payload))
        val firstText = blocks.filterIsInstance<FlowBlock.Paragraph>().firstOrNull()?.parts
            ?.filterIsInstance<FlowPart.Text>()?.joinToString("") { it.text }.orEmpty()
        assertTrue(firstText.isNotBlank(), "TALLROW §13.1: 表外テキストが最初の段落として復元されていない")
        val tableIndex = blocks.indexOfFirst { it is FlowBlock.Table }
        val paraIndex = blocks.indexOfFirst {
            it is FlowBlock.Paragraph &&
                it.parts.filterIsInstance<FlowPart.Text>().joinToString("") { p -> p.text }.isNotBlank()
        }
        assertTrue(tableIndex >= 0 && paraIndex in 0 until tableIndex, "TALLROW §13.1: 段落は表より前")

        // 縦統合: 1 論理行あたりの date セル（parts.size==3: Text・LineBreak・Text）出現を骨格照合。
        val xmlHandler = ToXMLContentHandler()
        TikaInputStream.get(ByteArrayInputStream(data)).use { s ->
            JtdParser().parse(s, xmlHandler, Metadata(), ParseContext())
        }
        val xml = xmlHandler.toString().replace(Regex(">\\s+<"), "><")
        println("TALLROW RAIL output: tables=${count(xml, "<table\\b")} tr=${count(xml, "<tr\\b")} br=${count(xml, "<br\\b")}")

        val firstTable = blocks.filterIsInstance<FlowBlock.Table>().first()
        assertTrue(firstTable.rows.size >= 4, "表の論理行が足りない: ${firstTable.rows.size}")
        // 論理行ヘッダ 2 行（月・日付/行事）に続く 2 論理行は縦統合済みでなければならない。
        for (rowIdx in 2..3) {
            val cells = firstTable.rows[rowIdx].cells
            val stacked = cells.count { cell ->
                cell.parts.size == 3 &&
                    cell.parts[0] is FlowPart.Text &&
                    cell.parts[1] is FlowPart.LineBreak &&
                    cell.parts[2] is FlowPart.Text
            }
            assertTrue(stacked >= 6, "TALLROW §13.2: 縦統合日付セル不足 row#$rowIdx stacked=$stacked")
        }
        // 統合後 tr 数は半減していること（物理 2 行/論理 1 行。§13.2）
        val trBefore = count(xml, "<tr\\b")
        assertTrue(trBefore < 90, "TALLROW §13.2: 物理行分裂残存 tr=$trBefore")
        println("TALLROW RAIL PASS: prologue=ok stacked=ok tr=$trBefore")
    }
}
