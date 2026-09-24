package com.hiyowa.tika.jtd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OpenJTD rjtd-core/src/header_text.rs の inline テスト移植（Step 4 / P-H+F）。
 *
 * フィクスチャ合成ヘルパ（header_payload / textv_slot / tcnt_slot / marker_span）は
 * 実測レイアウト（ヘッダ・フッタ混在文書と同型）を模した Rust 版の忠実再現。
 */
class HeaderTextReaderTest {

    companion object {
        private val TEXT_SEGMENT_NAME = listOf(0x5465, 0x7874, 0x562E, 0x3031) // "TextV.01"
        private val TCNT_SEGMENT_NAME = listOf(0x5443, 0x6E74, 0x562E, 0x3031) // "TCntV.01"
        private const val HEADER_SLOT_PITCH_WORDS = 128
        private val TAIL_TABLE_WORDS = listOf(0x001B, 0x0000, 0x0001, 0x0000, 0x0001, 0x0000, 0x005D, 0x00A9)
    }

    private fun extendUnits(bytes: MutableList<Byte>, units: List<Int>) {
        for (unit in units) {
            bytes.add(((unit shr 8) and 0xFF).toByte())
            bytes.add((unit and 0xFF).toByte())
        }
    }

    private fun utf16Units(text: String): List<Int> =
        text.map { it.code }

    private fun rawSpan(text: String): List<Int> = utf16Units(text)

    private fun markerSpan(body: String): List<Int> {
        val span = mutableListOf(
            0x001C, 0x0010, 0x001A, 0x0000, 0x0000, 0x0001, 0x0002, 0x0024, 0x0001, 0x0002, 0x0025,
            0x0001, 0x0000, 0x0026, 0x0005, 0x0001, 0x0000, 0x0000, 0x0000, 0x0000, 0xFFFF, 0x0000,
            0x001A, 0x0000, 0x0010, 0x001F,
        )
        span.addAll(utf16Units(body))
        span.add(0x000A)
        return span
    }

    private fun textvSlot(span: List<Int>): List<Int> {
        val slot = mutableListOf<Int>()
        slot.addAll(TEXT_SEGMENT_NAME)
        slot.add(0x0000)
        slot.add(span.size and 0xFFFF)
        slot.addAll(span)
        while (slot.size < HEADER_SLOT_PITCH_WORDS) slot.add(0x0000)
        return slot
    }

    private fun tcntSlot(): List<Int> {
        val slot = mutableListOf<Int>()
        slot.addAll(TCNT_SEGMENT_NAME)
        slot.add(0x0000)
        slot.add(0x0000)
        while (slot.size < HEADER_SLOT_PITCH_WORDS) slot.add(0x0000)
        return slot
    }

    private fun headerPayload(slots: List<List<Int>>): ByteArray {
        val bytes = mutableListOf<Byte>()
        extendUnits(
            bytes,
            listOf(0x5373, 0x6D67, 0x562E, 0x3031, 0x0000, 0x0001, 0x0000, 0x0100, 0x0000, slots.size),
        )
        for (slot in slots) extendUnits(bytes, slot)
        extendUnits(bytes, TAIL_TABLE_WORDS)
        return bytes.toByteArray()
    }

    private fun cfbWithStreams(entries: Map<String, ByteArray>): ByteArray {
        POIFSFileSystem().use { fs ->
            for ((path, payload) in entries) {
                val parts = path.trimStart('/').split('/')
                var dir = fs.root as org.apache.poi.poifs.filesystem.DirectoryEntry
                for (i in 0 until parts.size - 1) {
                    dir = dir.createDirectory(parts[i])
                }
                dir.createDocument(parts.last(), ByteArrayInputStream(payload))
            }
            val out = ByteArrayOutputStream()
            fs.writeFilesystem(out)
            return out.toByteArray()
        }
    }

    @Test
    fun resolvesPageNumberPlaceholderInRawSpan() {
        val payload = headerPayload(listOf(textvSlot(rawSpan("- ? -")), tcntSlot()))
        val text = HeaderTextReader.parseHeaderText(payload)
        assertNotNull(text, "valid header should parse")
        assertEquals(listOf("- 1 -"), text.lines)
        assertEquals("- 1 -", text.text())
        assertFalse(text.text().contains('?'), "プレースホルダ `?` が未解決で残っている")
    }

    @Test
    fun rawSpanStripsTrailingLineBreakAndKeepsInnerOne() {
        val innerBreak = "銀河の夜\n天文台"
        val trailingBreak = innerBreak + "\n"
        val payload = headerPayload(listOf(textvSlot(rawSpan("Ver.1.0")), textvSlot(rawSpan(trailingBreak))))
        val text = HeaderTextReader.parseHeaderText(payload)
        assertNotNull(text)
        assertEquals(listOf("Ver.1.0", innerBreak), text.lines)
        assertEquals("Ver.1.0\n銀河の夜\n天文台", text.text())
    }

    @Test
    fun sharedP1HelpersKeepRawPathConventions() {
        val raw = rawSpan("- ? -")
        assertNull(DocumentTextParser.firstTextMarker(raw, 0, raw.size))
        val decoded = DocumentTextParser.decodePrologueUnits(raw, 0, raw.size)
        assertNotNull(decoded)
        assertEquals("- ? -", decoded.text)
        assertEquals(raw.size, decoded.boundary)
        assertEquals("- 1 -", decoded.text.replace('?', '1'))
    }

    @Test
    fun markerSpanYieldsOnlyRunTextAfterTextRunMarker() {
        val payload = headerPayload(listOf(textvSlot(markerSpan("（別添：銀河鉄道様式）")), tcntSlot()))
        val text = HeaderTextReader.parseHeaderText(payload)
        assertNotNull(text)
        assertEquals(listOf("（別添：銀河鉄道様式）"), text.lines)
        val joined = text.text()
        assertFalse(joined.contains('$'), "0x0024 スタイル語のリーク")
        assertFalse(joined.contains('%'), "0x0025 スタイル語のリーク")
        assertFalse(joined.contains('\uFFFF'), "0xffff 無効スカラーのリーク")
        assertFalse(joined.contains('\u001C'), "0x001c レコード開始マーカーのリーク")
    }

    @Test
    fun multipleSpansRestoreInStreamOrder() {
        val payload = headerPayload(
            listOf(textvSlot(rawSpan("- ? -")), tcntSlot(), textvSlot(rawSpan("Ver.1.0")), tcntSlot()),
        )
        val text = HeaderTextReader.parseHeaderText(payload)
        assertNotNull(text)
        assertEquals(listOf("- 1 -", "Ver.1.0"), text.lines)
        assertEquals("- 1 -\nVer.1.0", text.text())
    }

    @Test
    fun tailTableAndSegmentNamesDoNotLeakIntoText() {
        val payload = headerPayload(listOf(textvSlot(rawSpan("- ? -")), tcntSlot()))
        val joined = HeaderTextReader.parseHeaderText(payload)?.text()
        assertNotNull(joined)
        for (junk in charArrayOf('\u001B', '\u005D', '\u00A9', '\u0001', '\u0000')) {
            assertFalse(joined.contains(junk), "末尾位置/所有テーブル語が出力に漏れている: $junk")
        }
        assertFalse(joined.contains("CntV"), "セグメント名 TCntV.01 のリーク")
        assertFalse(joined.contains("TextV"), "セグメント名 TextV.01 のリーク")
    }

    @Test
    fun skipsHitsWithNonzeroSlotHeadWord() {
        val brokenSlot = mutableListOf(0x5465, 0x7874, 0x562E, 0x3031, 0x0001, 0x0000)
        while (brokenSlot.size < HEADER_SLOT_PITCH_WORDS) brokenSlot.add(0x0000)
        val payload = headerPayload(listOf(brokenSlot, textvSlot(rawSpan("Ver.1.0"))))
        val text = HeaderTextReader.parseHeaderText(payload)
        assertNotNull(text, "異常スロット先導語はスキップして復元を続ける")
        assertEquals(listOf("Ver.1.0"), text.lines)
    }

    @Test
    fun returnsNoneWhenAllSpansAreEmpty() {
        val payload = headerPayload(listOf(textvSlot(emptyList()), tcntSlot()))
        assertNull(HeaderTextReader.parseHeaderText(payload))
    }

    @Test
    fun returnsNoneWhenLinesAreBlankOnly() {
        val payload = headerPayload(listOf(textvSlot(rawSpan(" \u3000 "))))
        assertNull(HeaderTextReader.parseHeaderText(payload))
    }

    @Test
    fun rejectsMalformedLayouts() {
        // マジック不一致
        assertNull(HeaderTextReader.parseHeaderText("TextV.01........".toByteArray(Charsets.ISO_8859_1)))
        // ヘッダ語数不足
        val short = mutableListOf<Byte>()
        extendUnits(short, listOf(0x5373, 0x6D67, 0x562E, 0x3031, 0x0000))
        assertNull(HeaderTextReader.parseHeaderText(short.toByteArray()))
        // 奇数バイト
        assertNull(HeaderTextReader.parseHeaderText("SsmgV.01\u0000".toByteArray(Charsets.ISO_8859_1)))
        // span 長が末尾を超える切詰め
        val truncated = mutableListOf<Byte>()
        extendUnits(
            truncated,
            listOf(0x5373, 0x6D67, 0x562E, 0x3031, 0x0000, 0x0001, 0x0000, 0x0100, 0x0000, 0x0001, 0x5465, 0x7874, 0x562E, 0x3031, 0x0000, 0x7FFF),
        )
        extendUnits(truncated, TAIL_TABLE_WORDS)
        assertNull(HeaderTextReader.parseHeaderText(truncated.toByteArray()))
        // span 長 0x0186 のオーバーフロー宣言（末尾超過）
        val overflow = mutableListOf<Byte>()
        extendUnits(
            overflow,
            listOf(0x5373, 0x6D67, 0x562E, 0x3031, 0x0000, 0x0001, 0x0000, 0x0100, 0x0000, 0x0001, 0x5465, 0x7874, 0x562E, 0x3031, 0x0000, 0x0186),
        )
        extendUnits(overflow, TAIL_TABLE_WORDS)
        assertNull(HeaderTextReader.parseHeaderText(overflow.toByteArray()))
    }

    @Test
    fun readHeaderTextReturnsNoneWhenStreamMissing() {
        val bytes = cfbWithStreams(mapOf("/Unused" to "payload".toByteArray(Charsets.ISO_8859_1)))
        assertNull(HeaderTextReader.readHeaderText(bytes))
    }

    @Test
    fun readHeaderTextExtractsFromCfbStream() {
        val payload = headerPayload(
            listOf(textvSlot(rawSpan("- ? -")), tcntSlot(), textvSlot(rawSpan("Ver.1.0")), tcntSlot()),
        )
        val bytes = cfbWithStreams(mapOf("/Header" to payload))
        val text = HeaderTextReader.readHeaderText(bytes)
        assertNotNull(text, "should extract header text")
        assertEquals(listOf("- 1 -", "Ver.1.0"), text.lines)
        assertEquals("- 1 -\nVer.1.0", text.text())
    }

    @Test
    fun jtdParserPrependsHeaderTextBeforeBody() {
        // P-H+F 出力契約: ヘッダ行を本文の前に置き、本文と空行 1 行で区切る（Tika 準拠）
        val header = headerPayload(
            listOf(textvSlot(rawSpan("- ? -")), tcntSlot(), textvSlot(rawSpan("Ver.1.0")), tcntSlot()),
        )
        val body = mutableListOf<Byte>()
        body.addAll("SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        body.add(0x00)
        body.add(0x1f)
        body.addAll("銀河鉄道\n".toByteArray(Charsets.UTF_16BE).toList())

        val data = cfbWithStreams(
            mapOf(
                "/Header" to header,
                "/DocumentText" to body.toByteArray(),
            ),
        )

        val parser = JtdParser()
        val handler = BodyContentHandler(-1)
        val metadata = Metadata()
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, "phf.jtd")

        TikaInputStream.get(ByteArrayInputStream(data)).use { stream ->
            parser.parse(stream, handler, metadata, ParseContext())
        }

        assertTrue(handler.toString().startsWith("- 1 -\nVer.1.0\n\n銀河鉄道"), "実際の出力: ${handler.toString()}")
        assertTrue(handler.toString().contains("銀河鉄道"))
    }
}
