package com.hiyowa.tika.jtd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.IOException
import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.DocumentEntry
import org.apache.poi.poifs.filesystem.DocumentInputStream
import org.apache.poi.poifs.filesystem.POIFSFileSystem

/**
 * CFB (Compound File Binary / OLE2) のマジック番号。
 *
 * FileMagic に委譲せずバイト列を直接比較するため（移行レポート の方針）、
 * この値を JtdFormatDetector が判定の入口で使う。
 */
val CFB_MAGIC = byteArrayOf(
    0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
    0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
)

/**
 * 単一ストリーム読み出しの上限（バイト）。
 *
 * TODO: これは読み出し超過（graffiti 防止）の簡易上限。正式なメモリ予算機構は後続の Step で導入予定。
 */
const val MAX_STREAM_BYTES: Long = 64L * 1024 * 1024

private const val READ_BUFFER_SIZE = 8192

/**
 * JTD コンテナ（CFB / OLE2）の開封・読み出しヘルパ。Rust 版 cfb / jtd::reader相当。
 *
 * POI の [POIFSFileSystem] は [java.io.Closeable] なため、呼び出し側が確実にクローズできるように
 * [withFileSystem] を併用する設計とする。
 */

/**
 * バイト列を CFB コンテナとして開く。
 *
 * FileMagic は使わず直接 [POIFSFileSystem] を構築する（指示通り）。
 * マジック非一致・破損ファイルでは POI が [IOException]（NotOLE2FileException 等）を投げるため、
 * そのまま呼び出し側に伝播させる。
 */
fun open(data: ByteArray): POIFSFileSystem = POIFSFileSystem(ByteArrayInputStream(data))

/**
 * [open] して [block] を実行し、結果を帰る。例外の有無にかかわらず必ずクローズする。
 */
fun <T> withFileSystem(data: ByteArray, block: (POIFSFileSystem) -> T): T =
    open(data).use { fs -> block(fs) }

/**
 * "/DocumentText" 形式のフルパスでストリームを読み出す。
 *
 * @return ストリームが存在すればそのバイト列、存在しなければ null
 * @throws IOException 読み出し中の I/O エラー、または [MAX_STREAM_BYTES] 超過時
 */
fun readStream(fs: POIFSFileSystem, path: String): ByteArray? {
    val segments = path.trimStart('/').split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return null

    // 親ディレクトリを順路で辿る（POI は存在しないエントリで null を返す場合と
    // FileNotFoundException を投げる API 実装があるため、両対応する）
    var dir: DirectoryEntry = fs.root
    for (i in 0 until segments.size - 1) {
        val entry = try {
            dir.getEntry(segments[i])
        } catch (e: FileNotFoundException) {
            return null
        }
        if (entry !is DirectoryEntry) return null
        dir = entry
    }

    val leaf = segments.last()
    val entry = try {
        dir.getEntry(leaf)
    } catch (e: FileNotFoundException) {
        return null
    }
    if (entry !is DocumentEntry) return null

    // 上限超過は読み出し前に弾く（巨大バッファを確保させる前に失敗させるため）
    if (entry.size > MAX_STREAM_BYTES) {
        throw IOException("stream '$path' exceeds the $MAX_STREAM_BYTES byte limit")
    }

    return DocumentInputStream(entry).use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(READ_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            total += read
            // TODO: 本格的なメモリ予算機構は後続の Step で導入予定
            if (total > MAX_STREAM_BYTES) {
                throw IOException("stream '$path' exceeds the $MAX_STREAM_BYTES byte limit")
            }
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }
}

/**
 * 全ストリームのフルパスを列挙する。
 *
 * ルート直下は "/Name"、サブディレクトリ配下は "/Dir/Name" の形式で、
 * 名前の大文字小文字は POI の保存値をそのまま返す。ディレクトリ自身は含めない。
 */
fun listStreams(fs: POIFSFileSystem): List<String> {
    val result = mutableListOf<String>()
    collectStreams(fs.root, "/", result)
    return result
}

private fun collectStreams(dir: DirectoryEntry, prefix: String, out: MutableList<String>) {
    for (entry in dir.entries) {
        val name = entry.name
        if (entry is DirectoryEntry) {
            collectStreams(entry, "$prefix$name/", out)
        } else {
            out.add("$prefix$name")
        }
    }
}
