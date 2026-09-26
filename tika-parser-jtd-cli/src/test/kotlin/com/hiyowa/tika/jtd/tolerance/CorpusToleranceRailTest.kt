package com.hiyowa.tika.jtd.tolerance

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * 実世界コーパス耐性検証・合格率自動算出テストレール。
 *
 * docs/internal/CORPUS-TOLERANCE-VERIFICATION-TEST-DESIGN.md に基づくオプトインテスト。
 * 通常の `./gradlew test` ではスキップされ、`-Pgatagate.corpus=true` 指定時のみ実行される。
 */
@EnabledIfSystemProperty(named = "gatagate.corpus", matches = "true")
class CorpusToleranceRailTest {

    @Test
    fun verifyCorpusToleranceBenchmark() {
        val root = File(System.getProperty("gatagate.root", System.getProperty("user.dir")))
        val corpusDir = File(root, "testdata/corpus")
        assertTrue(corpusDir.isDirectory, "コーパスディレクトリが存在しません: ${corpusDir.absolutePath}")

        val configProp = System.getProperty("gatagate.corpus.config")
        val configFile = if (configProp != null) File(configProp) else null
        val report = CorpusToleranceVerifier.verify(corpusDir, configFile)

        // 標準出力へ Markdown レポートを出力
        println("\n=== 実世界コーパス耐性検証レポート ===")
        println(report.markdownTable)
        println("======================================\n")

        // build/reports/corpus-tolerance.md に保存
        val reportsDir = File(root, "build/reports")
        reportsDir.mkdirs()
        val reportFile = File(reportsDir, "corpus-tolerance.md")
        reportFile.writeText(report.markdownTable + "\n", Charsets.UTF_8)

        // 詳細 TSV の出力
        val detailsFile = File(reportsDir, "corpus-tolerance-details.tsv")
        val tsvHeader = "category\trelPath\tcoverage\tpassed\tdocChars\tjtdChars\tsheetStatus\n"
        val tsvRows = report.results.joinToString("\n") { r ->
            "${r.category}\t${r.relPath}\t${"%.4f".format(r.coverage)}\t${r.passed}\t${r.docCharCount}\t${r.jtdCharCount}\t${r.sheetStatus}"
        }
        detailsFile.writeText(tsvHeader + tsvRows + "\n", Charsets.UTF_8)

        // マルチシート突合ステータスレポート（build/reports/multi-sheet-alignment-report.md）に保存
        val multiSheetReportFile = File(reportsDir, "multi-sheet-alignment-report.md")
        multiSheetReportFile.writeText(report.multiSheetReport + "\n", Charsets.UTF_8)
        val partialCount = report.multiSheetStatuses.count { it.isPartial }
        if (partialCount > 0) {
            System.err.println("\n[INFO] 一部シートのみ突合したマルチシート文書が $partialCount 件あります。詳細は build/reports/multi-sheet-alignment-report.md を参照してください。\n")
        }

        // 標準6カテゴリ定義がすべて揃っている場合は全 510 files の存在を検証
        val standardCategories = setOf("裁判所", "教育委員会・学校", "中央省庁・国", "地方自治体", "都道府県警察", "オーナー実文書")
        val evaluatedCategories = report.categorySummaries.map { it.category }.toSet()
        if (evaluatedCategories.containsAll(standardCategories)) {
            assertEquals(510, report.totalSummary.totalFiles, "標準6カテゴリの検証対象ファイル数が 510 件と一致しません")
        } else {
            assertTrue(report.totalSummary.totalFiles > 0, "検証対象ファイルが 0 件です")
        }

        // 合格率および平均一致度の最低限の妥当性検証
        assertTrue(
            report.totalSummary.passRate >= 0.50,
            "全体の合格率が 50% を下回っています: ${report.totalSummary.passRate}",
        )
        assertTrue(
            report.totalSummary.avgCoverage >= 0.85,
            "全体の平均一致度が 0.85 を下回っています: ${report.totalSummary.avgCoverage}",
        )
    }
}
