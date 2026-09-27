package com.hiyowa.tika.jtd.tools

import java.nio.file.Path
import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.DocumentEntry
import org.apache.poi.poifs.filesystem.Entry
import org.apache.poi.poifs.filesystem.POIFSFileSystem

/**
 * OLE2 (CFB) コンテナのディレクトリツリーを決定論的に走査する。
 *
 * 各ディレクトリ内で「サブディレクトリ（名前 lowercase 昇順）を再帰 →
 * その後に文書（名前 lowercase 昇順）」の順で出力する。
 *
 * @property kind "DIR" または "DOC"
 * @property path コンテナ内フルパス（先頭 "/" 付き、例 "/One/Deep/math"）
 * @property size DOC はバイトサイズ、DIR は -1
 * @property childCount DIR は直接子エントリ数、DOC は 0
 */
data class OleEntry(val kind: String, val path: String, val size: Long, val childCount: Int)

object OleScanner {

    /** 名前による決定論的順序（lowercase 昇順 → 原文昇順）。 */
    private val nameOrder: Comparator<Entry> =
        compareBy<Entry> { it.name.lowercase() }.thenBy { it.name }

    /** [input] の OLE2 コンテナを走査し、全エントリを決定論的な順序で返す。走査不能は例外を伝播。 */
    fun scan(input: Path): List<OleEntry> {
        val fs = POIFSFileSystem(input.toFile())
        try {
            val out = mutableListOf<OleEntry>()
            visit(fs.root, "", out)
            return out
        } finally {
            fs.close()
        }
    }

    /**
     * [dir] のサブツリーを走査する。[path] はディレクトリのフルパス（"/" 付き）。
     * ルートは path="" として呼ばれ、その行は出力しない（ルート自身のエントリは列挙する）。
     */
    private fun visit(dir: DirectoryEntry, path: String, out: MutableList<OleEntry>) {
        val children = dir.asSequence().toList()
        if (path.isNotEmpty()) {
            out += OleEntry("DIR", path, -1L, children.size)
        }
        for (sub in children.filter { it.isDirectoryEntry() }.sortedWith(nameOrder)) {
            val entry = sub as DirectoryEntry
            visit(entry, childPath(path, entry.name), out)
        }
        for (doc in children.filter { it.isDocumentEntry() }.sortedWith(nameOrder)) {
            val entry = doc as DocumentEntry
            out += OleEntry("DOC", childPath(path, entry.name), entry.getSize().toLong(), 0)
        }
    }

    private fun childPath(path: String, name: String): String =
        if (path.isEmpty()) "/$name" else "$path/$name"
}
