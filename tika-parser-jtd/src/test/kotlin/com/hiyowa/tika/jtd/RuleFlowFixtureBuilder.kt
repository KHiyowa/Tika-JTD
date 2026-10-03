package com.hiyowa.tika.jtd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.POIFSFileSystem

/**
 * RFC 0013 流路／表構造化テスト共用の合成バイナリフィクスチャビルダー。
 *
 * RFC 0009 レコード（`0x001c class len payload… len 0x0000 class 0x001f`）と
 * RFC 0013 §9.7 統制サンプルのイベント周期（`P:008f → CELL… → WALL`）を
 * 語列から直接組み立てる。本文文言は『銀河鉄道の夜』（青空文庫・パブリックドメイン）から引用し、
 * 実コーパスの本文・ファイル名・組織名は持ち込まない（AGENTS.md 準拠）。
 */
internal object RuleFlowFixtureBuilder {

    /** 0x000e 行境界（WALL）。 */
    const val WALL = 0x000e

    /** 0x000a 流路折返し（WRAP）。 */
    const val WRAP = 0x000a

    /** テスト共通の表右端座標。 */
    const val TABLE_RIGHT = 160

    /** RFC 0009 汎用レコード：w0=0x001c / w1=class / w2=len / payload / (len,0,class,0x001f)。 */
    fun record(clazz: Int, body: List<Int>): List<Int> {
        val len = 3 + body.size + 4
        return listOf(0x001c, clazz, len) + body + listOf(len, 0x0000, clazz, 0x001f)
    }

    /**
     * class 0x0030 セルヘッダ（12語固定）: `001c 0030 000c 0000 b0 b1 <語6> <語7> …`。
     * 語6=セル種別フラグ（実測 0x0001=完結テキストセル / 0x0000=上方継続断片 /
     * 0x00ff=標準・非セル）、語7=継続状態フラグ（実測 0x0002=下方継続アンカー）。
     * 既定は従来形（語6=0x00ff・語7=0x0000）。
     */
    fun cell(left: Int, right: Int, flags: Int = 0x00ff, flags2: Int = 0x0000): List<Int> =
        record(0x0030, listOf(0x0000, left, right, flags, flags2))

    /** class 0x0010 段落ヘッダ（w4 は段落整形系。len=13 代表形）。 */
    fun paragraph(w4: Int = 0x0026): List<Int> =
        record(0x0010, listOf(0x0000, w4, 0x0001, 0x0001, 0xffff, 0x0000))

    /**
     * class 0x0010 `w4=0x008f` 行ヘッダ（Span Reaffirm）。
     * w5 は RFC 0013 §3.1 の算術 $w_5 = 4(n-1)+3$。
     * サブエントリは `[tag, 0x0013, 0x0000, 0x0000]×(n-1)` + 末尾スタイル語。
     */
    fun rowHeader(n: Int, w6: Int = TABLE_RIGHT, tail: Int = 0x0022, w4: Int = 0x008f): List<Int> {
        val w5 = 4 * (n - 1) + 3
        val body = mutableListOf(0x0000, w4, w5, w6)
        repeat(n - 1) { body.addAll(listOf(w6, 0x0013, 0x0000, 0x0000)) }
        body.add(tail)
        return record(0x0010, body)
    }

    /** class 0x0020 表→段落セクション遷移（len=12 代表形）。 */
    fun transition(): List<Int> =
        record(0x0020, listOf(0x0000, 0x0010, 0x0002, 0x0000, 0x0001))

    /** w7 自動番号（丸数字 8 / 箇条書き）付き段落ヘッダ（`(1) ` 接頭辞が発生する形）。 */
    fun numberingParagraph(style: Int = 0x0002): List<Int> =
        record(0x0010, listOf(0x0000, 0x0026, 0x0005, 0x00a3, 0x0002, style, 0xffff, 0x0000))

    /** テキスト語列（UTF-16BE 1語=1文字。非 BMP は使わない）。 */
    fun text(s: String): List<Int> = s.map { it.code }

    /**
     * 表示インライン（ルビ基底・脚注アンカー）:
     * `[001c 0001 0007 0000 0000 sel 001d] 本文 [001e 0005 0000 0001 001f]`。
     */
    fun inline(base: String, selector: Int = 0x0003): List<Int> =
        listOf(0x001c, 0x0001, 0x0007, 0x0000, 0x0000, selector, 0x001d) +
            text(base) +
            listOf(0x001e, 0x0005, 0x0000, 0x0001, 0x001f)

    /**
     * スキップインライン（ルビ注記）:
     * `[001c 0001 0007 0x0000 0001 sel 001d] ルビ [001e 0005 0000 0001 001f]`。
     */
    fun skippedRuby(ruby: String, selector: Int = 0x0082): List<Int> =
        listOf(0x001c, 0x0001, 0x0007, 0x0000, 0x0001, selector, 0x001d) +
            text(ruby) +
            listOf(0x001e, 0x0005, 0x0000, 0x0001, 0x001f)

    /** 語列を SsmgV.01 マジック付きストリームバイト列化。 */
    fun stream(words: List<Int>): ByteArray {
        val bytes = ByteArrayOutputStream()
        "SsmgV.01".toByteArray(Charsets.ISO_8859_1).let { bytes.write(it) }
        for (word in words) {
            bytes.write((word shr 8) and 0xFF)
            bytes.write(word and 0xFF)
        }
        return bytes.toByteArray()
    }

    /** マジック + 0x001f + テキストだけの平文ストリーム（無構造フォールバック用）。 */
    fun markerText(s: String): ByteArray = stream(listOf(0x001f) + text(s))

    /** raw-text 単一セグメント（SsmgV.01 / TextV.01 / w[9]=1・本文は units[16..]）。 */
    fun rawTextSegment(s: String): ByteArray {
        val content = text(s)
        // SsmgV.01 はバイトマジック（4語分）なので先頭8バイトに直接載せる。
        val header = ByteArray(20)
        "SsmgV.01".toByteArray(Charsets.ISO_8859_1).copyInto(header, 0)
        header[18] = 0x00
        header[19] = 0x01 // w[9] = raw-text 単一セグメント
        val segName = intArrayOf(0x5465, 0x7874, 0x562E, 0x3031) // "TextV.01"
        val wordsOut = mutableListOf<Int>()
        segName.forEach { wordsOut.add(it) }
        wordsOut.add(0x0000)
        wordsOut.add(content.size) // w[15] = 本文語数
        wordsOut.addAll(content)
        val out = ByteArrayOutputStream()
        out.write(header)
        wordsOut.forEach {
            out.write((it shr 8) and 0xFF)
            out.write(it and 0xFF)
        }
        return out.toByteArray()
    }

    /** /LayoutBoxText（TextV.01 span・128語ピッチ・raw span）。 */
    fun layoutBoxStream(blocks: List<String>): ByteArray {
        val pitch = 128
        val words = mutableListOf<Int>()
        // SsmgV.01 マジック（4語） + w[4..8] ゼロ + w[9]=ブロック数
        "SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList().chunked(2).forEach { pair ->
            words.add(((pair[0].toInt() and 0xFF) shl 8) or (pair[1].toInt() and 0xFF))
        }
        while (words.size < 9) words.add(0x0000)
        words.add(blocks.size)
        for (block in blocks) {
            val span = text(block)
            val spanLen = span.size.coerceAtMost(122)
            words.addAll(listOf(0x5465, 0x7874, 0x562E, 0x3031)) // "TextV.01"
            words.add(0x0000)
            words.add(spanLen)
            words.addAll(span.take(spanLen))
            val padTarget = ((words.size - 10 + pitch - 1) / pitch) * pitch + 10
            while (words.size < padTarget) words.add(0x0000)
        }
        return streamNoMagic(words)
    }

    /** マジックなしで語列をバイト列化。 */
    fun streamNoMagic(words: List<Int>): ByteArray {
        val bytes = ByteArrayOutputStream()
        for (word in words) {
            bytes.write((word shr 8) and 0xFF)
            bytes.write(word and 0xFF)
        }
        return bytes.toByteArray()
    }

    /** テスト用 CFB コンテナ（複数ストリーム）。 */
    fun cfb(entries: Map<String, ByteArray>): ByteArray {
        POIFSFileSystem().use { fs ->
            for ((path, payload) in entries) {
                val parts = path.trimStart('/').split('/')
                var dir: DirectoryEntry = fs.root
                for (i in 0 until parts.size - 1) {
                    val existing = try {
                        dir.getEntry(parts[i])
                    } catch (e: FileNotFoundException) {
                        null
                    }
                    dir = if (existing is DirectoryEntry) existing else dir.createDirectory(parts[i])
                }
                dir.createDocument(parts.last(), ByteArrayInputStream(payload))
            }
            val out = ByteArrayOutputStream()
            fs.writeFilesystem(out)
            return out.toByteArray()
        }
    }

    /** マルチシート（DocItemInfo）ストリーム（ObjectSheetsTest と同一レイアウト）。 */
    fun docItemInfo(entries: List<Pair<String, Long>>): ByteArray {
        fun le32(value: Long) = listOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        )
        val data = mutableListOf<Byte>()
        data.addAll(le32(entries.size.toLong()))
        for ((name, docsId) in entries) {
            data.addAll(le32(1))
            val units = name.toByteArray(Charsets.UTF_16LE).toList()
            data.addAll(le32((units.size / 2).toLong()))
            data.addAll(units)
            data.addAll(le32(docsId))
            data.addAll(ByteArray(26).toList())
        }
        return data.toByteArray()
    }
}
