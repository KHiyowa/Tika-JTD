package com.hiyowa.tika.jtd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * OpenJTD rjtd-core/src/layout_box_text.rs の inline テスト移植（Step 4b / P2）。
 *
 * フィクスチャは囲み枠レイアウト同型・本文は銀河鉄道の夜合成。
 */
class LayoutBoxTextReaderTest {

    companion object {
        private const val BLOCK_PITCH_WORDS = 128
        private const val TEXT_SEGMENT_NAME_WORDS = 4
        private const val SPAN_HEADER_WORDS = 2
    }

    private fun extendUnits(bytes: MutableList<Byte>, units: List<Int>) {
        for (unit in units) {
            bytes.add(((unit shr 8) and 0xFF).toByte())
            bytes.add((unit and 0xFF).toByte())
        }
    }

    private fun utf16Units(text: String): List<Int> = text.map { it.code }

    private fun fixtureBlock1(): List<Int> {
        val prologue = "「ジョバンニさん。あなたはわかっているのでしょう。」"
        val inline = "「やっぱり星だとジョバンニは思いましたが」"
        val block = mutableListOf<Int>()
        block.addAll(utf16Units("$prologue\n"))
        block.addAll(listOf(0x001C, 0x0000, 0x000C, 0x0000, 0x0007, 0x00BE, 0x020D, 0x0000, 0x000C, 0x0000, 0x0000, 0x001F))
        block.addAll(listOf(0x001C, 0x0001, 0x0007, 0x0000, 0x0000, 0x0003, 0x001D))
        block.addAll(utf16Units(inline))
        block.add(0x001E)
        return block
    }

    private fun fixtureRawBlock(): List<Int> = utf16Units("「うん。ぼく牛乳をとりながら見てくるよ。」")

    private fun tailTableWords(): List<Int> =
        listOf(0x0000, 0x0063, 0x0000, 0x0001, 0x0000, 0x0001, 0x0000, 0x0000, 0x0000, 0x0049, 0x0000, 0x0001)

    /** 2ブロック + 末尾テーブル列のフルペイロード（ブロック数は第1引数で宣言）。 */
    private fun layoutBoxTextPayload(blockCount: Int): ByteArray {
        val block1 = fixtureBlock1()
        val block2 = fixtureRawBlock()
        val bytes = mutableListOf<Byte>()
        extendUnits(bytes, listOf(0x5373, 0x6D67, 0x562E, 0x3031, 0x0000, 0x0002, 0x0000, 0x0100, 0x0000, blockCount))
        for (block in listOf(block1, block2)) {
            extendUnits(bytes, listOf(0x5465, 0x7874, 0x562E, 0x3031)) // "TextV.01"
            extendUnits(bytes, listOf(0x0000, block.size))
            extendUnits(bytes, block)
            val used = TEXT_SEGMENT_NAME_WORDS + SPAN_HEADER_WORDS + block.size
            extendUnits(bytes, List(BLOCK_PITCH_WORDS - used) { 0x0000 })
        }
        extendUnits(bytes, tailTableWords())
        return bytes.toByteArray()
    }

    private fun cfbWithStreams(entries: Map<String, ByteArray>): ByteArray {
        POIFSFileSystem().use { fs ->
            for ((path, payload) in entries) {
                val parts = path.trimStart('/').split('/')
                var dir: DirectoryEntry = fs.root
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
    fun decodesMixedBlockAsPrologueThenInlineText() {
        val block = fixtureBlock1()
        val decoded = LayoutBoxTextReader.decodeLayoutBoxSpan(block, 0, block.size)
        assertEquals(
            "「ジョバンニさん。あなたはわかっているのでしょう。」\n「やっぱり星だとジョバンニは思いましたが」",
            decoded,
        )
        assertFalse(decoded.contains('\u00BE'))
        assertFalse(decoded.contains('\u020D'))
    }

    @Test
    fun decodesMarkerlessBlockAsFullRawText() {
        val block = fixtureRawBlock()
        assertEquals(
            "「うん。ぼく牛乳をとりながら見てくるよ。」",
            LayoutBoxTextReader.decodeLayoutBoxSpan(block, 0, block.size),
        )
    }

    @Test
    fun parsesBlocksJoinsWithNewlinesAndIgnoresTailTable() {
        val payload = layoutBoxTextPayload(2)
        val text = assertNotNull(LayoutBoxTextReader.parseLayoutBoxText(payload))

        assertEquals(2, text.blocks.size)
        assertEquals(
            "「ジョバンニさん。あなたはわかっているのでしょう。」\n「やっぱり星だとジョバンニは思いましたが」",
            text.blocks[0],
        )
        assertEquals("「うん。ぼく牛乳をとりながら見てくるよ。」", text.blocks[1])
        val joined = text.text()
        assertEquals(
            "「ジョバンニさん。あなたはわかっているのでしょう。」\n「やっぱり星だとジョバンニは思いましたが」\n「うん。ぼく牛乳をとりながら見てくるよ。」",
            joined,
        )
        assertFalse(joined.contains('c'))
        assertFalse(joined.contains('I'))
        assertFalse(joined.contains('\u00BE'))
        assertFalse(joined.contains('\u020D'))
    }

    @Test
    fun blockCountBoundsReadingToDeclaredAreaOnly() {
        val payload = layoutBoxTextPayload(2)
        // ヘッダのブロック数（w[9]）を 1 に書き換える
        payload[18] = 0x00
        payload[19] = 0x01

        val text = assertNotNull(LayoutBoxTextReader.parseLayoutBoxText(payload))
        assertEquals(1, text.blocks.size)
        assertTrue(text.text().startsWith("「ジョバンニさん。あなたはわかっているのでしょう。」"))
        assertFalse(text.text().contains("「うん。ぼく牛乳をとりながら見てくるよ。」"))
    }

    @Test
    fun readLayoutBoxTextReturnsNoneWhenStreamMissing() {
        val bytes = cfbWithStreams(mapOf("/Unused" to "payload".toByteArray(Charsets.ISO_8859_1)))
        assertNull(LayoutBoxTextReader.readLayoutBoxText(bytes))
    }

    @Test
    fun readLayoutBoxTextExtractsFromCfbStream() {
        val payload = layoutBoxTextPayload(2)
        val bytes = cfbWithStreams(mapOf("/LayoutBoxText" to payload))
        val text = assertNotNull(LayoutBoxTextReader.readLayoutBoxText(bytes), "should extract box text")
        assertEquals(2, text.blocks.size)
        assertTrue(text.text().startsWith("「ジョバンニさん。あなたはわかっているのでしょう。」"))
    }

    @Test
    fun rejectsMalformedLayouts() {
        // マジック不一致
        assertNull(LayoutBoxTextReader.parseLayoutBoxText("TextV.01........".toByteArray(Charsets.ISO_8859_1)))
        // ヘッダのみ（ブロック数 0）は空テキストとして成立
        val headerOnly = mutableListOf<Byte>()
        extendUnits(headerOnly, listOf(0x5373, 0x6D67, 0x562E, 0x3031, 0x0000, 0x0002, 0x0000, 0x0100, 0x0000, 0x0000))
        val text = assertNotNull(LayoutBoxTextReader.parseLayoutBoxText(headerOnly.toByteArray()))
        assertTrue(text.blocks.isEmpty())
        // 宣言があるが切り詰め（領域不足）
        val truncated = mutableListOf<Byte>()
        extendUnits(truncated, listOf(0x5373, 0x6D67, 0x562E, 0x3031, 0x0000, 0x0002, 0x0000, 0x0100, 0x0000, 0x0004, 0x5465, 0x7874))
        assertNull(LayoutBoxTextReader.parseLayoutBoxText(truncated.toByteArray()))
        // 領域内だが TextV.01 セグメント名破壊（構造崩壊）
        val corrupted = layoutBoxTextPayload(1)
        "XXXXXXXX".toByteArray(Charsets.ISO_8859_1).copyInto(corrupted, 20)
        assertNull(LayoutBoxTextReader.parseLayoutBoxText(corrupted))
    }

    @Test
    fun sharedP1HelpersKeepMarkerAndBoundarySemantics() {
        val block = fixtureBlock1()
        val prose = "「ジョバンニさん。あなたはわかっているのでしょう。」".length
        assertEquals(prose + 1, DocumentTextParser.firstTextMarker(block, 0, block.size))

        val raw = mutableListOf<Int>()
        raw.addAll(utf16Units("前置きA\n"))
        raw.addAll(listOf(0x0000, 0x0000))
        raw.addAll(utf16Units("前置きB"))
        raw.add(0x0019) // 制御境界: 打ち切り
        raw.addAll(utf16Units("ノイズ"))
        val decoded = assertNotNull(DocumentTextParser.decodePrologueUnits(raw, 0, raw.size))
        assertEquals("前置きA\n前置きB", decoded.text)
        assertEquals(raw.size - 4, decoded.boundary)
    }

    @Test
    fun jtdParserAppendsBoxTextWithMarker() {
        // P2 契約: 本文の後に「\n」「※枠内テキスト\n」+ 枠テキスト連結（非空のときのみ）
        val body = mutableListOf<Byte>()
        body.addAll("SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        body.add(0x00)
        body.add(0x1f)
        body.addAll("銀河鉄道\n".toByteArray(Charsets.UTF_16BE).toList())

        val data = cfbWithStreams(
            mapOf(
                "/DocumentText" to body.toByteArray(),
                "/LayoutBoxText" to layoutBoxTextPayload(2),
            ),
        )

        val parser = JtdParser()
        val handler = BodyContentHandler(-1)
        val metadata = Metadata()

        TikaInputStream.get(ByteArrayInputStream(data)).use { stream ->
            parser.parse(stream, handler, metadata, ParseContext())
        }

        val out = handler.toString()
        assertTrue(out.startsWith("銀河鉄道\n"), "実際の出力冒頭: $out")
        assertTrue(out.contains("\n※枠内テキスト\n「ジョバンニさん。"), "実際の出力: $out")
        assertFalse(out.contains('c') && out.contains('I'), "末尾テーブル語リーク")
    }

    @Test
    fun emptyBoxTextDoesNotEmitMarker() {
        // /LayoutBoxText が空（ブロック数 0）なら枠注記を出さない
        val body = mutableListOf<Byte>()
        body.addAll("SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        body.add(0x00)
        body.add(0x1f)
        body.addAll("銀河鉄道\n".toByteArray(Charsets.UTF_16BE).toList())

        val headerOnly = mutableListOf<Byte>()
        extendUnits(headerOnly, listOf(0x5373, 0x6D67, 0x562E, 0x3031, 0x0000, 0x0002, 0x0000, 0x0100, 0x0000, 0x0000))

        val data = cfbWithStreams(
            mapOf(
                "/DocumentText" to body.toByteArray(),
                "/LayoutBoxText" to headerOnly.toByteArray(),
            ),
        )

        val parser = JtdParser()
        val handler = BodyContentHandler(-1)
        val metadata = Metadata()

        TikaInputStream.get(ByteArrayInputStream(data)).use { stream ->
            parser.parse(stream, handler, metadata, ParseContext())
        }

        assertFalse(handler.toString().contains("※枠内テキスト"))
    }
}
