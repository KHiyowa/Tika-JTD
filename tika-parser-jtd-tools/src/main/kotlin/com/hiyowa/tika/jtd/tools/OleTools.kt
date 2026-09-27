package com.hiyowa.tika.jtd.tools

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.DocumentEntry
import org.apache.poi.poifs.filesystem.DocumentInputStream
import org.apache.poi.poifs.filesystem.Entry
import org.apache.poi.poifs.filesystem.POIFSFileSystem

/**
 * OLE2 補助 CLI。
 *
 * - `list <file>`: エントリツリー（DIR/DOC・子数・サイズ）を1行ずつ出力
 * - `magic <file>`: 各ストリーム先頭512バイトのマジック識別ラベルのみ出力
 * - `dump <file> <outDir>`: 文書をフラット展開
 *
 * ストリームの中身バイト列は一切出力しない（PII/原本漏洩防止）。
 */
object OleTools {

    private const val HEADER_WINDOW = 512
    private const val EXIT_OK = 0
    private const val EXIT_USAGE = 2
    private const val EXIT_ERROR = 1

    fun run(args: List<String>, out: PrintStream, err: PrintStream): Int {
        val cmd = args.firstOrNull() ?: return printUsage(err)
        return try {
            when (cmd) {
                "list" -> handleList(args.drop(1), out, err)
                "magic" -> handleMagic(args.drop(1), out, err)
                "dump" -> handleDump(args.drop(1), out, err)
                else -> printUsage(err)
            }
        } catch (e: Exception) {
            err.println("error: ${e.javaClass.simpleName}: ${e.message}")
            EXIT_ERROR
        }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        kotlin.system.exitProcess(run(args.toList(), System.out, System.err))
    }

    private fun printUsage(err: PrintStream): Int {
        err.println("usage: ole-tools <list|magic> <file>")
        err.println("       ole-tools dump <file> <outDir>")
        return EXIT_USAGE
    }

    private fun requireFile(rest: List<String>, err: PrintStream): Path? {
        val p = rest.getOrNull(0)?.let { Paths.get(it) } ?: return null
        return if (Files.isRegularFile(p)) p else null
    }

    private fun handleList(rest: List<String>, out: PrintStream, err: PrintStream): Int {
        val file = requireFile(rest, err) ?: return printUsage(err)
        for (e in OleScanner.scan(file)) {
            out.println(
                "%s  %s%s".format(
                    e.kind,
                    e.path,
                    if (e.kind == "DIR") "  children=${e.childCount}" else "  size=${e.size}",
                ),
            )
        }
        return EXIT_OK
    }

    private fun handleMagic(rest: List<String>, out: PrintStream, err: PrintStream): Int {
        val file = requireFile(rest, err) ?: return printUsage(err)
        val fs = POIFSFileSystem(file.toFile())
        try {
            val docs = mutableListOf<Pair<String, DocumentEntry>>()
            visitDocuments(fs.root, "", docs)
            for ((path, doc) in docs) {
                val header = DocumentInputStream(doc).use { it.readNBytes(HEADER_WINDOW) }
                val d = StreamMagic.detect(header)
                out.println("%s @%08x  size=%d  %s".format(d.label, d.offset, doc.getSize().toLong(), path))
            }
            return EXIT_OK
        } finally {
            fs.close()
        }
    }

    private fun handleDump(rest: List<String>, out: PrintStream, err: PrintStream): Int {
        val file = requireFile(rest, err) ?: return printUsage(err)
        val outDirArg = rest.getOrNull(1) ?: return printUsage(err)
        val result = OleDumper.dump(file, Paths.get(outDirArg))
        for (p in result.written) {
            out.println("WROTE ${p.fileName}  size=${Files.size(p)}")
        }
        for ((name, size) in result.skipped) {
            out.println("SKIP $name  size=$size")
        }
        return EXIT_OK
    }

    private fun visitDocuments(dir: DirectoryEntry, path: String, out: MutableList<Pair<String, DocumentEntry>>) {
        val children = dir.asSequence().toList()
        val nameOrder = compareBy<Entry> { it.name.lowercase() }.thenBy { it.name }
        for (sub in children.filter { it.isDirectoryEntry() }.sortedWith(nameOrder)) {
            val entry = sub as DirectoryEntry
            visitDocuments(entry, childPath(path, entry.name), out)
        }
        for (doc in children.filter { it.isDocumentEntry() }.sortedWith(nameOrder)) {
            val entry = doc as DocumentEntry
            out += childPath(path, entry.name) to entry
        }
    }

    private fun childPath(path: String, name: String): String =
        if (path.isEmpty()) "/$name" else "$path/$name"
}
