package com.hiyowa.tika.jtd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OpenJTD rjtd-core/src/sheet.rs と rjtd-model（マルチシート連結・脚注ペアリング）の
 * テスト移植（Step 6）。本文は銀河鉄道の夜（青空文庫、パブリックドメイン）。
 *
 * 出力契約（移行レポート 第 6 節②③）:
 * - マルチシート: シート名を素で単独行 + シート間 \n\n（Tika Excel 準拠）
 * - 脚注: ラベルと直後 TextRun の構造ペアリング（label + 半角スペース + 本文）
 */
class ObjectSheetsTest {

    private fun le32(value: Long): List<Byte> = listOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte(),
    )

    private fun utf16LeUnits(text: String): List<Byte> =
        text.toByteArray(Charsets.UTF_16LE).toList()

    private fun docItemInfo(entries: List<Pair<String, Long>>): ByteArray {
        val data = mutableListOf<Byte>()
        data.addAll(le32(entries.size.toLong()))
        for ((name, docsId) in entries) {
            data.addAll(le32(1))
            val units = utf16LeUnits(name)
            data.addAll(le32(units.size / 2L))
            data.addAll(units)
            data.addAll(le32(docsId))
            data.addAll(ByteArray(26).toList())
        }
        return data.toByteArray()
    }

    private fun markerDocumentText(text: String): ByteArray {
        val bytes = mutableListOf<Byte>()
        bytes.addAll("SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        bytes.add(0x00)
        bytes.add(0x1f)
        bytes.addAll(text.toByteArray(Charsets.UTF_16BE).toList())
        return bytes.toByteArray()
    }

    private fun cfbWithStreams(entries: Map<String, ByteArray>): ByteArray {
        POIFSFileSystem().use { fs ->
            for ((path, payload) in entries) {
                val parts = path.trimStart('/').split('/')
                var dir: DirectoryEntry = fs.root
                for (i in 0 until parts.size - 1) {
                    // 同一ディレクトリパスが複数エントリで現れるため既存を再利用する
                    // （createDirectory は同名の子が既にあると Duplicate name を投げる）。
                    // POI の getEntry は存在しない場合 null ではなく FileNotFoundException。
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

    // ---- sheet.rs: UTF-16LE レコード解析 ----

    @Test
    fun parsesDocItemInfoSynthetic() {
        val res = ObjectSheetsReader.parseDocItemInfo(docItemInfo(listOf("午後の授業" to 3L, "活版所" to 4L)))
        assertEquals(2, res.size)
        assertEquals("午後の授業" to 3L, res[0])
        assertEquals("活版所" to 4L, res[1])
    }

    @Test
    fun parseDocItemInfoRejectsTruncatedStream() {
        assertFailsWith<InvalidDataException> { ObjectSheetsReader.parseDocItemInfo(byteArrayOf(0x01, 0x00)) }
    }

    @Test
    fun parsesSheetInfoRootNameFromTailBackward() {
        // 末尾逆向き: [len u32LE][UTF-16LE 名前]
        val name = "銀河タイトル"
        val tail = le32(name.length.toLong()) + utf16LeUnits(name)
        val data = ByteArray(16) + tail.toByteArray()
        assertEquals("銀河タイトル", ObjectSheetsReader.parseSheetInfoRootName(data))
        // 短すぎるデータは null
        assertNull(ObjectSheetsReader.parseSheetInfoRootName(ByteArray(4)))
    }

    @Test
    fun parsesDocItemInfo2OriginalPaths() {
        val path = "C:\\ Ichitaro\\銀河.jtd"
        val data = le32(path.length.toLong()).toByteArray() + utf16LeUnits(path)
        val paths = ObjectSheetsReader.parseDocItemInfo2(data)
        assertEquals(listOf(path), paths)
    }

    @Test
    fun readDocumentSheetsDefaultsToSingleRootSheet() {
        val bytes = cfbWithStreams(mapOf("/DocumentText" to markerDocumentText("銀河鉄道の夜\n宮沢賢治")))
        val sheets = ObjectSheetsReader.readDocumentSheets(bytes)
        assertTrue(sheets.isEmpty())
    }

    // ---- JtdParser: マルチシート連結（モデル契約） ----

    @Test
    fun jtdParserRestoresMultiSheetsInConcatOrder() {
        val data = cfbWithStreams(
            mapOf(
                "/DocumentText" to markerDocumentText("銀河鉄道の夜\n宮沢賢治"),
                "/ObjectSheets/DocSheet/DocItemInfo" to docItemInfo(listOf("一、午後の授業" to 0L, "二、活版所" to 1L)),
                "/ObjectSheets/DocSheet/DOCS_0000/DocumentText" to markerDocumentText("カムパネルラが手をあげました。"),
                "/ObjectSheets/DocSheet/DOCS_0001/DocumentText" to markerDocumentText("ジョバンニは窓をあけました。"),
            ),
        )

        val parser = JtdParser()
        val handler = org.apache.tika.sax.BodyContentHandler(-1)
        val metadata = org.apache.tika.metadata.Metadata()
        org.apache.tika.io.TikaInputStream.get(ByteArrayInputStream(data)).use { stream ->
            parser.parse(stream, handler, metadata, org.apache.tika.parser.ParseContext())
        }

        val out = handler.toString()
        // sheets[0] = ルート（既定名「タイトル」）、シート間は \n\n、名前は素で単独行
        assertEquals(
            "タイトル\n銀河鉄道の夜\n宮沢賢治\n\n一、午後の授業\nカムパネルラが手をあげました。\n\n二、活版所\nジョバンニは窓をあけました。",
            out.trim(),
        )
    }

    // ---- JtdParser: 脚注ペアリング ----

    private fun footnoteStream(pairs: List<Pair<String, String>>): ByteArray {
        // 実証済み inline 記法: [1c 01 07 00 00 sel 1d]ラベル[1e 05 00 01 1f] 本文
        // （Rust `inlineTextSelector` の文脈検証 [0x001c,0x0001,0x0007,0x0000,0x0000,sel]
        //  と rjtd-model テスト `footnote_stream_with_entries` と同一レイアウト）
        val bytes = mutableListOf<Byte>()
        bytes.addAll("SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        for ((label, body) in pairs) {
            bytes.add(0x00)
            bytes.add(0x1c)
            bytes.add(0x00)
            bytes.add(0x01)
            bytes.add(0x00)
            bytes.add(0x07)
            bytes.add(0x00)
            bytes.add(0x00)
            bytes.add(0x00)
            bytes.add(0x00)
            bytes.add(0x00)
            bytes.add(0x01) // FOOTNOTE_ANCHOR_SELECTOR
            bytes.add(0x00)
            bytes.add(0x1d)
            bytes.addAll(label.toByteArray(Charsets.UTF_16BE).toList())
            bytes.add(0x00)
            bytes.add(0x1e)
            bytes.add(0x00)
            bytes.add(0x05)
            bytes.add(0x00)
            bytes.add(0x00)
            bytes.add(0x00)
            bytes.add(0x01)
            bytes.add(0x00)
            bytes.add(0x1f)
            bytes.addAll(body.toByteArray(Charsets.UTF_16BE).toList())
        }
        return bytes.toByteArray()
    }

    @Test
    fun jtdParserAppendsPairedFootnotesForSingleSheet() {
        val data = cfbWithStreams(
            mapOf(
                "/DocumentText" to markerDocumentText("銀河鉄道の夜\n"),
                "/Footnote" to footnoteStream(listOf("[1]" to "カムパネルラ。", "[2]" to "ジョバンニ。")),
            ),
        )

        val parser = JtdParser()
        val handler = org.apache.tika.sax.BodyContentHandler(-1)
        val metadata = org.apache.tika.metadata.Metadata()
        org.apache.tika.io.TikaInputStream.get(ByteArrayInputStream(data)).use { stream ->
            parser.parse(stream, handler, metadata, org.apache.tika.parser.ParseContext())
        }

        val out = handler.toString()
        assertTrue(out.startsWith("銀河鉄道の夜"), "actual: $out")
        assertTrue(out.contains("\n\n[1] カムパネルラ。\n[2] ジョバンニ。"), "actual: $out")
    }

    @Test
    fun missingFootnoteStreamKeepsOutputUnchanged() {
        val data = cfbWithStreams(mapOf("/DocumentText" to markerDocumentText("銀河鉄道の夜\n")))

        val parser = JtdParser()
        val handler = org.apache.tika.sax.BodyContentHandler(-1)
        val metadata = org.apache.tika.metadata.Metadata()
        org.apache.tika.io.TikaInputStream.get(ByteArrayInputStream(data)).use { stream ->
            parser.parse(stream, handler, metadata, org.apache.tika.parser.ParseContext())
        }

        val out = handler.toString()
        // /Footnote 欠落時は本文のみの従来出力と同一（ゲート契約）
        assertTrue(out.startsWith("銀河鉄道の夜"), "actual: $out")
        assertFalse(out.contains("[1]"), "存在しない脚注が出てはならない")
    }
}
