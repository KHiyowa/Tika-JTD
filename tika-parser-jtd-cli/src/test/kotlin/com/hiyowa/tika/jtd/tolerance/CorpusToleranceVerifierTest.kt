package com.hiyowa.tika.jtd.tolerance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [CorpusToleranceVerifier] のカバレッジ計算・前処理ロジックの単体テスト。
 * コーパス実ファイルに依存せず、常に実行される。
 */
class CorpusToleranceVerifierTest {

    @Test
    fun preprocessNormalizesNfkcAndRemovesWhitespaceAndArtifacts() {
        // 全角英数・半角カナの NFKC 正規化、空白・制御文字の除去、DOC アーティファクトの除去
        val docInput = "ＡＢＣ\u3000１２３\t\nｶﾅ&#32;\\-12\\-**太字**\u2500"
        val docClean = CorpusToleranceVerifier.preprocessText(docInput, isDoc = true)
        // ＡＢＣ -> ABC, １２３ -> 123, ｶﾅ -> カナ, &#32; -> 除去, \-12\- -> 除去, ** -> 除去, 罫線 \u2500 -> 除去
        assertEquals("ABC123カナ太字", docClean)

        val jtdInput = "ＡＢＣ\n１２３　ｶﾅ\u200b\u2501"
        val jtdClean = CorpusToleranceVerifier.preprocessText(jtdInput, isDoc = false)
        assertEquals("ABC123カナ", jtdClean)
    }

    @Test
    fun computeCoverageExactMatch() {
        val coverage = CorpusToleranceVerifier.computeCoverage("abcdef", "abcdef")
        assertEquals(1.0, coverage)
    }

    @Test
    fun computeCoverageWithExtraJtdTextDoesNotPenalize() {
        // JTD 側がより多くの文字（例: 連番や枠テキスト）を拾えていても DOC の再現率は 100%
        val coverage = CorpusToleranceVerifier.computeCoverage("abc", "abcdef")
        assertEquals(1.0, coverage)
    }

    @Test
    fun computeCoverageWithMissingCharactersInJtd() {
        // DOC に 10 文字中、JTD に 8 文字存在（2 文字欠落）
        val coverage = CorpusToleranceVerifier.computeCoverage("abcdefghij", "abcdefgh")
        assertEquals(0.8, coverage)
    }

    @Test
    fun computeCoverageBagOfCharactersIgnoresOrder() {
        // テキスト順序が入れ替わっていても、マルチセット（文字頻度）が一致していれば 100%
        val coverage = CorpusToleranceVerifier.computeCoverage("一太郎JTD文書", "文書一太郎JTD")
        assertEquals(1.0, coverage)
    }

    @Test
    fun computeCoverageDuplicateCharactersHandledCorrectly() {
        // DOC に 'a' が 3 個、JTD に 'a' が 2 個ある場合、1 個欠落
        val coverage = CorpusToleranceVerifier.computeCoverage("aaab", "aab")
        assertEquals(3.0 / 4.0, coverage)
    }

    @Test
    fun computeCoverageEmptyStrings() {
        assertEquals(1.0, CorpusToleranceVerifier.computeCoverage("", ""))
        assertEquals(1.0, CorpusToleranceVerifier.computeCoverage("", "abc"))
    }

    @Test
    fun resolvesSheetNamesForMultiSheetDocument() {
        var root = java.io.File(System.getProperty("gatagate.root", System.getProperty("user.dir")))
        if (!java.io.File(root, "testdata").isDirectory && root.parentFile != null) {
            root = root.parentFile
        }
        val syuron = java.io.File(root, "testdata/corpus/owner-jtd/multi-sheet/修論.jtd")
        if (syuron.isFile) {
            val sheetNames = CorpusToleranceVerifier.resolveSheetNames(syuron)
            assertTrue(sheetNames.isNotEmpty(), "修論.jtd のシート名が空です")
            println("修論 sheets: $sheetNames")
        }
    }

    @Test
    fun alignsDocsAccordingToSheetOrder() {
        val base = "修論"
        val docFiles = listOf(
            java.io.File("修論_目次シート.doc"),
            java.io.File("修論_タイトルシート.doc"),
            java.io.File("修論_本文シート.doc"),
            java.io.File("修論_アブストラクトシート.doc"),
        )
        val sheetNames = listOf("タイトル", "アブストラクト", "本文", "目次")
        val aligned = CorpusToleranceVerifier.alignDocsBySheetNames(base, docFiles, sheetNames)

        assertEquals(
            listOf(
                "修論_タイトルシート.doc",
                "修論_アブストラクトシート.doc",
                "修論_本文シート.doc",
                "修論_目次シート.doc",
            ),
            aligned.map { it.name },
        )
    }

    @Test
    fun supportsPartialSheetDocsForMultiSheetDocument() {
        val base = "sample"
        val jtdFile = java.io.File("sample.jtd")
        val candidateDocs = listOf(
            java.io.File("sample_表紙.doc"),
            java.io.File("sample_本文.doc"),
            // "sample_付録.doc" が未変換
        )
        val sheetNames = listOf("表紙", "本文", "付録")

        val matchedDocs = mutableMapOf<String, java.io.File>()
        val remaining = candidateDocs.toMutableList()
        for (sheet in sheetNames) {
            val found = remaining.find { it.name.contains(sheet) }
            if (found != null) {
                matchedDocs[sheet] = found
                remaining.remove(found)
            }
        }
        val missing = sheetNames.filter { !matchedDocs.containsKey(it) }
        assertEquals(listOf("付録"), missing)

        val status = CorpusToleranceVerifier.MultiSheetStatus(
            category = "中央省庁・国",
            jtdFile = jtdFile,
            relPath = "gov-web-jtd/multi-sheet/sample.jtd",
            totalSheets = sheetNames.size,
            matchedSheets = matchedDocs.size,
            sheetNames = sheetNames,
            matchedDocs = matchedDocs.mapValues { it.value.name },
            missingSheetNames = missing,
            extraDocs = emptyList(),
        )

        assertTrue(status.isPartial)
        assertEquals(false, status.isFull)

        val report = CorpusToleranceVerifier.buildMultiSheetReport(listOf(status))
        assertTrue(report.contains("gov-web-jtd/multi-sheet/sample.jtd"))
        assertTrue(report.contains("Partial"))
        assertTrue(report.contains("2 / 3 sheets"))
        assertTrue(report.contains("`付録`"))
    }

    @Test
    fun parsesCategoriesTsvCorrectly() {
        val tempDir = java.nio.file.Files.createTempDirectory("corpus-test").toFile()
        try {
            val sub1 = java.io.File(tempDir, "sub1").apply { mkdirs() }
            val sub2 = java.io.File(tempDir, "sub2").apply { mkdirs() }
            val tsvFile = java.io.File(tempDir, "categories.tsv").apply {
                writeText(
                    """
                    # コメント行
                    
                    カテゴリA	sub1
                    カテゴリB	${sub2.absolutePath}
                    # 無視される行
                    """.trimIndent(),
                    Charsets.UTF_8,
                )
            }

            val categories = CorpusToleranceVerifier.resolveCategories(tempDir, tsvFile)
            assertEquals(2, categories.size)
            assertEquals("カテゴリA", categories[0].name)
            assertEquals(sub1.canonicalPath, categories[0].dir.canonicalPath)
            assertEquals("カテゴリB", categories[1].name)
            assertEquals(sub2.canonicalPath, categories[1].dir.canonicalPath)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun autoDiscoversCategoriesWithoutConfigFile() {
        val tempDir = java.nio.file.Files.createTempDirectory("corpus-zero-config").toFile()
        try {
            // subA: jtd と doc が両方ある -> 認識される
            val subA = java.io.File(tempDir, "cat-a").apply { mkdirs() }
            java.io.File(subA, "sample.jtd").writeText("dummy")
            java.io.File(subA, "sample.doc").writeText("dummy")

            // subB: doc しかない -> 除外
            val subB = java.io.File(tempDir, "cat-b").apply { mkdirs() }
            java.io.File(subB, "sample.doc").writeText("dummy")

            // .hidden: ドット始まり -> 除外
            val subDot = java.io.File(tempDir, ".hidden").apply { mkdirs() }
            java.io.File(subDot, "sample.jtd").writeText("dummy")
            java.io.File(subDot, "sample.doc").writeText("dummy")

            val categories = CorpusToleranceVerifier.resolveCategories(tempDir)
            assertEquals(1, categories.size)
            assertEquals("cat-a", categories[0].name)
            assertEquals(subA.canonicalPath, categories[0].dir.canonicalPath)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
