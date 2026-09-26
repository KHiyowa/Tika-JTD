package com.hiyowa.tika.jtd.tolerance

import com.hiyowa.tika.jtd.ObjectSheetsReader
import com.hiyowa.tika.jtd.cli.Cli
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.Normalizer

/**
 * 実世界コーパス耐性検証・合格率自動算出ハーネス。
 *
 * docs/internal/CORPUS-TOLERANCE-VERIFICATION-TEST-DESIGN.md に基づき、
 * JTD 文書と DOC エクスポート文書を突き合わせて一致度（Coverage）および合格率を算出する。
 */
object CorpusToleranceVerifier {

    /** カバレッジ合格閾値（95.0%） */
    const val PASS_THRESHOLD = 0.95

    data class CorpusCategory(
        val name: String,
        val dir: File,
    )

    data class TargetPair(
        val category: String,
        val jtdFile: File,
        val docFiles: List<File>,
        val sheetStatus: String = "single",
    )

    data class PairResult(
        val category: String,
        val relPath: String,
        val coverage: Double,
        val passed: Boolean,
        val docCharCount: Int,
        val jtdCharCount: Int,
        val sheetStatus: String = "single",
    )

    data class CategorySummary(
        val category: String,
        val totalFiles: Int,
        val passedFiles: Int,
        val passRate: Double,
        val avgCoverage: Double,
    )

    data class MultiSheetStatus(
        val category: String,
        val jtdFile: File,
        val relPath: String,
        val totalSheets: Int,
        val matchedSheets: Int,
        val sheetNames: List<String>,
        val matchedDocs: Map<String, String>, // sheetName to doc filename
        val missingSheetNames: List<String>,
        val extraDocs: List<String>, // doc filenames not matched to any sheet
    ) {
        val isFull: Boolean get() = missingSheetNames.isEmpty() && matchedSheets == totalSheets
        val isPartial: Boolean get() = !isFull && matchedSheets > 0
    }

    data class DiscoveryResult(
        val pairs: List<TargetPair>,
        val multiSheetStatuses: List<MultiSheetStatus>,
        val categories: List<CorpusCategory> = emptyList(),
    )

    data class VerificationReport(
        val results: List<PairResult>,
        val categorySummaries: List<CategorySummary>,
        val totalSummary: CategorySummary,
        val markdownTable: String,
        val multiSheetStatuses: List<MultiSheetStatus>,
        val multiSheetReport: String,
    )

    /**
     * コーパスディレクトリおよび設定ファイルから検証対象カテゴリ一覧を解決する。
     *
     * 探索優先順位:
     * 1. 明示的に指定された [configFile]
     * 2. システムプロパティ "gatagate.corpus.config"
     * 3. [corpusDir]/categories.tsv
     * 4. ゼロコンフィグ（Auto-discovery）: [corpusDir] 直下のサブディレクトリのうち、
     *    再帰配下に .jtd と .doc の双方が存在するディレクトリを自動検出
     */
    fun resolveCategories(corpusDir: File, configFile: File? = null): List<CorpusCategory> {
        val fileToRead = when {
            configFile != null && configFile.isFile -> configFile
            System.getProperty("gatagate.corpus.config")?.let { File(it) }?.isFile == true -> File(System.getProperty("gatagate.corpus.config")!!)
            corpusDir.resolve("categories.tsv").isFile -> corpusDir.resolve("categories.tsv")
            else -> null
        }

        if (fileToRead != null) {
            val list = mutableListOf<CorpusCategory>()
            fileToRead.forEachLine(Charsets.UTF_8) { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachLine
                val parts = line.split('\t')
                if (parts.size >= 2) {
                    val name = parts[0].trim()
                    val pathStr = parts[1].trim()
                    val dir = resolveCategoryDir(corpusDir, pathStr)
                    if (dir.isDirectory) {
                        list.add(CorpusCategory(name, dir))
                    }
                }
            }
            if (list.isNotEmpty()) {
                return list
            }
        }

        // ゼロコンフィグ: corpusDir 直下のサブディレクトリを自動検出
        val subDirs = corpusDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name }
            ?: emptyList()

        return subDirs.mapNotNull { dir ->
            val hasJtd = dir.walkTopDown().any { it.isFile && it.name.endsWith(".jtd", ignoreCase = true) }
            val hasDoc = dir.walkTopDown().any { it.isFile && it.name.endsWith(".doc", ignoreCase = true) }
            if (hasJtd && hasDoc) {
                CorpusCategory(name = dir.name, dir = dir)
            } else null
        }
    }

    /**
     * 指定されたパス文字列から対象ディレクトリを解決する。
     * プロジェクトルート相対、corpusDir 相対、絶対パスの順で探索する。
     */
    fun resolveCategoryDir(corpusDir: File, pathStr: String): File {
        val f = File(pathStr)
        if (f.isAbsolute) return f
        val fromCorpus = File(corpusDir, pathStr)
        if (fromCorpus.isDirectory) return fromCorpus
        val rootDir = corpusDir.parentFile ?: return fromCorpus
        val fromRoot = File(rootDir, pathStr)
        if (fromRoot.isDirectory) return fromRoot
        val parentOfRoot = rootDir.parentFile
        if (parentOfRoot != null) {
            val fromParent = File(parentOfRoot, pathStr)
            if (fromParent.isDirectory) return fromParent
        }
        return fromCorpus
    }

    /**
     * コーパスルートから検証対象ペアを探索する。
     */
    fun discoverPairs(corpusDir: File): List<TargetPair> =
        discover(corpusDir).pairs

    /**
     * コーパスルートから検証対象ペアおよびマルチシート突合ステータスを探索する。
     * カテゴリ直下のサブディレクトリ（single-sheet, multi-sheet 等）を再帰的に走査し、
     * 同一サブディレクトリ内に存在する JTD と DOC を自動ペアリングする。
     */
    fun discover(
        corpusDir: File,
        categories: List<CorpusCategory> = resolveCategories(corpusDir),
    ): DiscoveryResult {
        val pairs = mutableListOf<TargetPair>()
        val multiSheetStatuses = mutableListOf<MultiSheetStatus>()

        for (cat in categories) {
            val category = cat.name
            val categoryDir = cat.dir
            if (!categoryDir.isDirectory) continue

            // カテゴリ配下の全サブディレクトリを探索
            val subDirs = categoryDir.walkTopDown()
                .filter { it.isDirectory }
                .toList()

            for (dir in subDirs) {
                val files = dir.listFiles() ?: continue
                val docFiles = files.filter { it.isFile && it.name.endsWith(".doc", ignoreCase = true) }
                if (docFiles.isEmpty()) continue

                val jtdFiles = files.filter { it.isFile && it.name.endsWith(".jtd", ignoreCase = true) }
                    .sortedBy { it.name }

                for (jtd in jtdFiles) {
                    val relPath = corpusDir.toPath().relativize(jtd.toPath()).toString()
                    val resolution = resolveDocsForJtd(category, jtd, relPath, docFiles)
                    if (resolution.multiSheetStatus != null) {
                        multiSheetStatuses.add(resolution.multiSheetStatus)
                    }
                    if (resolution.docFiles.isNotEmpty()) {
                        val statusStr = resolution.multiSheetStatus?.let {
                            if (it.isFull) "${it.matchedSheets}/${it.totalSheets} (Full)"
                            else "${it.matchedSheets}/${it.totalSheets} (Partial)"
                        } ?: "single"
                        pairs.add(TargetPair(category, jtd, resolution.docFiles, statusStr))
                    }
                }
            }
        }

        return DiscoveryResult(pairs, multiSheetStatuses, categories)
    }

    data class DocResolution(
        val docFiles: List<File>,
        val multiSheetStatus: MultiSheetStatus?,
    )

    /**
     * 対象 JTD に対応する DOC ファイル群を探索し、マルチシートの場合はシート定義順に整列する。
     * 全シート分の DOC が揃っていない場合でも、存在する DOC だけで部分突合（Partial Coverage）として評価する。
     */
    fun resolveDocsForJtd(
        category: String,
        jtdFile: File,
        relPath: String,
        candidateDocs: List<File>,
    ): DocResolution {
        val base = normalizeNfc(jtdFile.name.substring(0, jtdFile.name.length - 4))

        // 1. 同名の完全一致 .doc があるか確認
        val exactDoc = candidateDocs.find {
            normalizeNfc(it.name.substring(0, it.name.length - 4)) == base
        }

        // 2. プレフィックスに一致する doc ファイル群を収集（[basename]_*.doc または exactDoc）
        val prefixDocs = candidateDocs.filter {
            val docName = normalizeNfc(it.name)
            docName.startsWith("${base}_") || (exactDoc != null && it == exactDoc)
        }

        val sheetNames = resolveSheetNames(jtdFile)

        // マルチシート文書（定義シート数が2以上）の場合
        if (sheetNames.size > 1) {
            // 関連 DOC が1つも存在しない場合は未作成としてスキップ
            if (prefixDocs.isEmpty() && exactDoc == null) {
                return DocResolution(emptyList(), null)
            }

            // DOC が存在する場合、各シートとの照合を行う
            val matchedDocs = mutableMapOf<String, File>()
            val remainingDocs = prefixDocs.toMutableList()

            for (sheetName in sheetNames) {
                val normSheet = normalizeNfc(sheetName)
                val found = remainingDocs.find { doc ->
                    val docBase = normalizeNfc(doc.name.substring(0, doc.name.length - 4))
                    docBase == "${base}_$normSheet" ||
                        docBase == "${base}_${normSheet}シート" ||
                        docBase.contains(normSheet)
                }
                if (found != null) {
                    matchedDocs[sheetName] = found
                    remainingDocs.remove(found)
                }
            }

            val missingSheets = sheetNames.filter { !matchedDocs.containsKey(it) }

            // 存在する DOC を JTD シート定義順に整列（余剰 doc があれば末尾に追加）
            val aligned = sheetNames.mapNotNull { matchedDocs[it] } + remainingDocs

            val status = MultiSheetStatus(
                category = category,
                jtdFile = jtdFile,
                relPath = relPath,
                totalSheets = sheetNames.size,
                matchedSheets = matchedDocs.size,
                sheetNames = sheetNames,
                matchedDocs = matchedDocs.mapValues { it.value.name },
                missingSheetNames = missingSheets,
                extraDocs = remainingDocs.map { it.name },
            )

            // 【案B】一部シートしか揃っていなくても、存在する DOC だけでカバレッジ計算対象とする
            return DocResolution(aligned, status)
        }

        // 単一シート文書の場合
        if (prefixDocs.isEmpty() && exactDoc != null) {
            return DocResolution(listOf(exactDoc), null)
        }
        if (prefixDocs.size == 1) {
            return DocResolution(prefixDocs, null)
        }
        if (prefixDocs.isNotEmpty()) {
            return DocResolution(prefixDocs.sortedBy { it.name }, null)
        }

        return DocResolution(if (exactDoc != null) listOf(exactDoc) else emptyList(), null)
    }

    /**
     * JTD のシート名定義順に従い、DOC ファイル群を整列する。
     */
    fun alignDocsBySheetNames(base: String, docFiles: List<File>, sheetNames: List<String>): List<File> {
        val matched = mutableListOf<File>()
        val remaining = docFiles.toMutableList()

        for (sheetName in sheetNames) {
            val normSheet = normalizeNfc(sheetName)
            val found = remaining.find { doc ->
                val docBase = normalizeNfc(doc.name.substring(0, doc.name.length - 4))
                docBase == "${base}_$normSheet" ||
                    docBase == "${base}_${normSheet}シート" ||
                    docBase.contains(normSheet)
            }
            if (found != null) {
                matched.add(found)
                remaining.remove(found)
            }
        }

        matched.addAll(remaining)
        return matched
    }

    /**
     * JTD ファイルからシート名の一覧を定義順に取得する。
     */
    fun resolveSheetNames(jtdFile: File): List<String> {
        return try {
            val bytes = jtdFile.readBytes()
            if (ObjectSheetsReader.hasMultipleSheets(bytes)) {
                ObjectSheetsReader.readDocumentSheets(bytes).map { it.name }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Tika CLI 経由でファイルをインプロセスでテキスト抽出する。
     */
    fun extractText(file: File): String {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val exit = Cli.run(listOf("--text", file.absolutePath), stdout, stderr)
        if (exit != 0) {
            val errStr = stderr.toString(Charsets.UTF_8.name())
            System.err.println("Failed to extract text from ${file.name} (exit $exit): $errStr")
        }
        return stdout.toString(Charsets.UTF_8.name())
    }

    /**
     * テキスト前処理パイプライン。
     *
     * 1. Unicode NFKC 正規化
     * 2. 空白類・制御文字の全除去
     * 3. (isDoc == true の場合) DOC 抽出アーティファクトのクリーニング
     */
    fun preprocessText(text: String, isDoc: Boolean): String {
        var processed = text

        if (isDoc) {
            // DOC 抽出アーティファクトのクリーニング
            // - HTML実体参照（&#32; 等）
            processed = processed.replace(Regex("&#[0-9]+;"), "")
            // - ページ番号エスケープ（\-1\- 等）
            processed = processed.replace(Regex("""\\+[-–—][0-9]+\\+[-–—]"""), "")
            // - Markdown/装飾マークアップ（** 等）
            processed = processed.replace("**", "")
        }

        // 1. Unicode NFKC 正規化
        processed = Normalizer.normalize(processed, Normalizer.Form.NFKC)

        // 2. 空白類・制御文字の全除去
        // \s, 全角空白 \u3000, 制御文字 \u0000-\u001f, \u007f-\u009f, ゼロ幅スペース \u200b, 不破空白 \u00a0
        // 罫線文字 \u2500-\u259f（Tika POI と JTD パーサーでの罫線再現差異を吸収）
        processed = processed.replace(
            Regex("[\\s\\u3000\\u0000-\\u001f\\u007f-\\u009f\\u200b\\u00a0\\u2500-\\u259f]"),
            "",
        )

        return processed
    }

    /**
     * 文字マルチセット（Bag of Characters）方式によるカバレッジ計算。
     *
     * Coverage(doc, jtd) = 1.0 - (Σ max(0, count_doc(c) - count_jtd(c))) / (Σ count_doc(c))
     *                    = (Σ min(count_doc(c), count_jtd(c))) / (Σ count_doc(c))
     */
    fun computeCoverage(docText: String, jtdText: String): Double {
        if (docText.isEmpty()) {
            return if (jtdText.isEmpty()) 1.0 else 1.0
        }

        val jtdCounts = HashMap<Char, Int>()
        for (c in jtdText) {
            jtdCounts[c] = (jtdCounts[c] ?: 0) + 1
        }

        var matched = 0
        for (c in docText) {
            val count = jtdCounts[c] ?: 0
            if (count > 0) {
                matched++
                jtdCounts[c] = count - 1
            }
        }

        return matched.toDouble() / docText.length
    }

    /**
     * 検証を実行し、結果およびサマリーを集計する。
     */
    fun verify(corpusDir: File, configFile: File? = null): VerificationReport {
        val categories = resolveCategories(corpusDir, configFile)
        val discovery = discover(corpusDir, categories)
        val pairs = discovery.pairs

        val results = pairs.parallelStream().map { pair ->
            val jtdRaw = extractText(pair.jtdFile)
            val docRaw = pair.docFiles.joinToString("\n") { extractText(it) }

            val jtdClean = preprocessText(jtdRaw, isDoc = false)
            val docClean = preprocessText(docRaw, isDoc = true)

            val coverage = computeCoverage(docClean, jtdClean)
            val passed = coverage >= PASS_THRESHOLD

            val relPath = corpusDir.toPath().relativize(pair.jtdFile.toPath()).toString()
            PairResult(
                category = pair.category,
                relPath = relPath,
                coverage = coverage,
                passed = passed,
                docCharCount = docClean.length,
                jtdCharCount = jtdClean.length,
                sheetStatus = pair.sheetStatus,
            )
        }.toList()

        // カテゴリ別集計（定義順）
        val categoryOrder = discovery.categories.map { it.name }
        val grouped = results.groupBy { it.category }

        val categorySummaries = categoryOrder.mapNotNull { category ->
            val list = grouped[category] ?: return@mapNotNull null
            val total = list.size
            val passed = list.count { it.passed }
            val passRate = if (total > 0) passed.toDouble() / total else 0.0
            val avgCoverage = if (total > 0) list.map { it.coverage }.average() else 0.0
            CategorySummary(
                category = category,
                totalFiles = total,
                passedFiles = passed,
                passRate = passRate,
                avgCoverage = avgCoverage,
            )
        }

        val totalFiles = results.size
        val totalPassed = results.count { it.passed }
        val totalPassRate = if (totalFiles > 0) totalPassed.toDouble() / totalFiles else 0.0
        val totalAvgCoverage = if (totalFiles > 0) results.map { it.coverage }.average() else 0.0

        val totalSummary = CategorySummary(
            category = "合計",
            totalFiles = totalFiles,
            passedFiles = totalPassed,
            passRate = totalPassRate,
            avgCoverage = totalAvgCoverage,
        )

        val markdown = buildMarkdownTable(categorySummaries, totalSummary)
        val multiSheetReport = buildMultiSheetReport(discovery.multiSheetStatuses)

        return VerificationReport(
            results = results,
            categorySummaries = categorySummaries,
            totalSummary = totalSummary,
            markdownTable = markdown,
            multiSheetStatuses = discovery.multiSheetStatuses,
            multiSheetReport = multiSheetReport,
        )
    }

    /**
     * マルチシート突合ステータスの Markdown レポートを生成する。
     */
    fun buildMultiSheetReport(statuses: List<MultiSheetStatus>): String {
        val sb = StringBuilder()
        sb.appendLine("# マルチシート DOC 突合ステータス レポート")
        sb.appendLine()
        if (statuses.isEmpty()) {
            sb.appendLine("評価対象のマルチシート文書はありません。")
            return sb.toString().trimEnd()
        }

        val partials = statuses.filter { it.isPartial }
        if (partials.isNotEmpty()) {
            sb.appendLine("⚠️ **注意**: 一部シートのみ DOC が存在するマルチシート文書が ${partials.size} 件あります。")
            sb.appendLine("当該シートの DOC テキストのみを用いて JTD との文字マルチセットカバレッジを算出しています。")
        } else {
            sb.appendLine("✅ 評価対象の全マルチシート文書において、全シート分の DOC ファイルが完全に揃っています。")
        }
        sb.appendLine()
        sb.appendLine("| カテゴリ | JTD ファイル | 突合ステータス | 突合シート数 / 全シート数 | 未突合シート一覧 |")
        sb.appendLine("| :--- | :--- | :--- | :--- | :--- |")
        for (st in statuses) {
            val statusBadge = if (st.isFull) "✅ Full" else "⚠️ Partial"
            val missingStr = if (st.missingSheetNames.isEmpty()) "-" else st.missingSheetNames.joinToString(", ") { "`$it`" }
            sb.appendLine("| **${st.category}** | `${st.relPath}` | $statusBadge | ${st.matchedSheets} / ${st.totalSheets} sheets | $missingStr |")
        }
        sb.appendLine()
        sb.appendLine("## ファイル別詳細内訳")
        for (st in statuses) {
            val badge = if (st.isFull) "✅ 完全突合 (Full)" else "⚠️ 一部シート突合 (Partial)"
            sb.appendLine()
            sb.appendLine("### `${st.relPath}` ($badge)")
            sb.appendLine("* **カテゴリ**: ${st.category}")
            sb.appendLine("* **シート充足度**: ${st.matchedSheets} / ${st.totalSheets} sheets")
            sb.appendLine("* **シート別突合状況**:")
            for (name in st.sheetNames) {
                val doc = st.matchedDocs[name]
                if (doc != null) {
                    sb.appendLine("  * `$name`: ✅ `$doc`")
                } else {
                    sb.appendLine("  * `$name`: ⚠️ **未突合（DOC なし）**")
                }
            }
            if (st.extraDocs.isNotEmpty()) {
                val extras = st.extraDocs.joinToString(", ") { "`$it`" }
                sb.appendLine("* **シート名と照合できなかった余剰 DOC**: $extras")
            }
        }

        return sb.toString().trimEnd()
    }

    private fun buildMarkdownTable(
        categorySummaries: List<CategorySummary>,
        totalSummary: CategorySummary,
    ): String {
        val sb = StringBuilder()
        sb.appendLine("| コーパス | ファイル数 | 合格率 | 平均一致度 |")
        sb.appendLine("| :--- | :--- | :--- | :--- |")
        for (cs in categorySummaries) {
            val passRateStr = "%.1f%%".format(cs.passRate * 100)
            val avgCovStr = "%.3f".format(cs.avgCoverage)
            sb.appendLine("| **${cs.category}** | ${cs.totalFiles} files | $passRateStr | $avgCovStr |")
        }
        val totalPassRateStr = "%.1f%%".format(totalSummary.passRate * 100)
        val totalAvgCovStr = "%.3f".format(totalSummary.avgCoverage)
        sb.appendLine("| **${totalSummary.category}** | **${totalSummary.totalFiles} files** | **$totalPassRateStr** | **$totalAvgCovStr** |")
        return sb.toString().trimEnd()
    }

    private fun normalizeNfc(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC)
}
