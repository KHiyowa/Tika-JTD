package com.hiyowa.tika.jtd.tools

import java.nio.file.Files
import java.nio.file.Path
import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.DocumentEntry
import org.apache.poi.poifs.filesystem.DocumentInputStream
import org.apache.poi.poifs.filesystem.Entry
import org.apache.poi.poifs.filesystem.POIFSFileSystem

/**
 * @property written 書き出したファイルのパス一覧（展開順）
 * @property skipped サイズ上限を超えてスキップした文書（サニタイズ前フルパス、サイズ）
 */
data class DumpResult(val written: List<Path>, val skipped: List<Pair<String, Long>>)

/**
 * OLE2 コンテナの全文書を [OleScanner] と同じ順序でフラット展開する。
 *
 * ファイル名は [sanitize] で制御文字・区切り・空白を無害化する。
 * サイズが [maxBytes] を超える文書は読まずスキップ記録する。
 */
object OleDumper {

    fun dump(input: Path, outDir: Path, maxBytes: Long = 64L shl 20): DumpResult {
        Files.createDirectories(outDir)
        val fs = POIFSFileSystem(input.toFile())
        try {
            val written = mutableListOf<Path>()
            val skipped = mutableListOf<Pair<String, Long>>()
            visit(fs.root, "", outDir, maxBytes, written, skipped)
            return DumpResult(written, skipped)
        } finally {
            fs.close()
        }
    }

    /** [dir] のサブツリーから文書のみを [OleScanner] と同じ順序で展開する。 */
    private fun visit(
        dir: DirectoryEntry,
        path: String,
        outDir: Path,
        maxBytes: Long,
        written: MutableList<Path>,
        skipped: MutableList<Pair<String, Long>>,
    ) {
        val children = dir.asSequence().toList()
        val nameOrder = compareBy<Entry> { it.name.lowercase() }.thenBy { it.name }
        for (sub in children.filter { it.isDirectoryEntry() }.sortedWith(nameOrder)) {
            val entry = sub as DirectoryEntry
            visit(entry, childPath(path, entry.name), outDir, maxBytes, written, skipped)
        }
        for (doc in children.filter { it.isDocumentEntry() }.sortedWith(nameOrder)) {
            val entry = doc as DocumentEntry
            val full = childPath(path, entry.name)
            val size = entry.getSize().toLong()
            if (size > maxBytes) {
                skipped.add(full to size)
                continue
            }
            val target = outDir.resolve(sanitize(full))
            DocumentInputStream(entry).use { ins ->
                Files.newOutputStream(target).use { ins.copyTo(it) }
            }
            written.add(target)
        }
    }

    /**
     * コンテナ内フルパスをファイルシステム上のファイル名に変換する。
     *
     * '/' と空白 → '_'、制御文字 0x01..0x1F → "%XX"（大文字16進、例 \u0001 → "%01"）。
     * 例: "/One/\u0003Contents" → "_One_%03Contents"、"/One/Deep/math" → "_One_Deep_math"。
     */
    fun sanitize(fullPath: String): String {
        val sb = StringBuilder(fullPath.length)
        for (c in fullPath) {
            when {
                c == '/' || c.isWhitespace() -> sb.append('_')
                c.code in 0x01..0x1F -> sb.append('%').append(String.format("%02X", c.code))
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun childPath(path: String, name: String): String =
        if (path.isEmpty()) "/$name" else "$path/$name"
}
