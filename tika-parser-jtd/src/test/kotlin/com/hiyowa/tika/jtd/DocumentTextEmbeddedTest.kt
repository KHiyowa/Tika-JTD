package com.hiyowa.tika.jtd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * OpenJTD rjtd-core/src/document_text.rs の埋め込みスキャン / マーカーレス raw /
 * 混在型プロローグのテスト移植（Step 4 完成系: 尤度フィル・span 上限・ Payload 経路）。
 *
 * 本文は宮沢賢治「銀河鉄道の夜」（青空文庫、パブリックドメイン）から合成。
 */
class DocumentTextEmbeddedTest {

    companion object {
        private const val GALAXY_P1 =
            "「ではみなさんは、そういうふうに川だと云われたり、乳の流れたあとだと云われたりしていたこのぼんやりと白いものがほんとうは何かご承知ですか。」先生は、黒板に吊した大きな黒い星座の図の、上から下へ白くけぶった銀河帯のようなところを指しながら、みんなに問をかけました。"
        private const val GALAXY_P2 =
            "カムパネルラが手をあげました。それから四五人手をあげました。ジョバンニも手をあげようとして、急いでそのままやめました。たしかにあれがみんな星だと、いつか雑誌で読んだのでしたが、このごろはジョバンニはまるで毎日教室でもねむく、本を読むひまも読む本もないので、なんだかどんなこともよくわからないという気持ちがするのでした。"
        private const val GALAXY_P3 = "ところが先生は早くもそれを見つけたのでした。"
        private const val GALAXY_P4 =
            "「ですからもしもこの天の川がほんとうに川だと考えるなら、その一つ一つの小さな星はみんなその川のそこの砂や砂利の粒にもあたるわけです。」"
        private const val GALAXY_P5 = "「ジョバンニさん。あなたはわかっているのでしょう。」"
        private const val GALAXY_P6 =
            "やっぱり星だとジョバンニは思いましたがこんどもすぐに答えることができませんでした。"
        private const val GALAXY_P7 = "「ああきっと一緒だよ。お母さん、窓をしめて置こうか。」"

        private val TEXTV01_NAME_WORDS = listOf(0x5465, 0x7874, 0x562E, 0x3031)
        private val SSMGV_HEAD_WORDS = listOf(0x5373, 0x6D67, 0x562E, 0x3031, 0x0000, 0x0001, 0x0000, 0x0100, 0x0000)
        private val RUN_RECORD_FOOTER_WORDS = listOf(0x001C, 0x0010, 0x0006, 0x0000, 0x0010, 0x001F)
    }

    private fun extendUnits(bytes: MutableList<Byte>, units: List<Int>) {
        for (unit in units) {
            bytes.add(((unit shr 8) and 0xFF).toByte())
            bytes.add((unit and 0xFF).toByte())
        }
    }

    private fun utf16Units(text: String): List<Int> = text.map { it.code }

    private fun cfbWithStream(path: String, payload: ByteArray): ByteArray {
        POIFSFileSystem().use { fs ->
            val parts = path.trimStart('/').split('/')
            var dir: DirectoryEntry = fs.root
            for (i in 0 until parts.size - 1) {
                dir = dir.createDirectory(parts[i])
            }
            dir.createDocument(parts.last(), ByteArrayInputStream(payload))
            val out = ByteArrayOutputStream()
            fs.writeFilesystem(out)
            return out.toByteArray()
        }
    }

    private fun markerlessRawPayload(segmentCount: Int, text: String, tail: List<Int>): ByteArray {
        val textContent = utf16Units(text)
        val bytes = mutableListOf<Byte>()
        extendUnits(bytes, listOf(0x5373, 0x6D67, 0x562E, 0x3031, 0x0000, 0x0001, 0x0000, 0x0100, 0x0000, segmentCount))
        extendUnits(bytes, TEXTV01_NAME_WORDS)
        extendUnits(bytes, listOf(0x0000, textContent.size))
        extendUnits(bytes, textContent)
        extendUnits(bytes, tail)
        return bytes.toByteArray()
    }

    private fun mixedProloguePayload(segmentCount: Int, prologue: List<Int>, body: List<Int>): ByteArray {
        val bytes = mutableListOf<Byte>()
        extendUnits(bytes, SSMGV_HEAD_WORDS)
        extendUnits(bytes, listOf(segmentCount))
        extendUnits(bytes, TEXTV01_NAME_WORDS)
        extendUnits(bytes, listOf(0x0000, prologue.size + body.size))
        extendUnits(bytes, prologue)
        extendUnits(bytes, body)
        return bytes.toByteArray()
    }

    // ---- 埋め込みスキャン（尤度フィル） ----

    @Test
    fun readsEmbeddedDocumentTextWhenNamedStreamAbsent() {
        val embedded = mutableListOf<Byte>()
        embedded.addAll("prefix SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        embedded.add(0x00)
        embedded.add(0x1f)
        embedded.addAll(utf16Units("Note").flatMap { listOf(((it shr 8) and 0xFF).toByte(), (it and 0xFF).toByte()) })

        val bytes = cfbWithStream("/JSSlipObject1", embedded.toByteArray())

        val payload = DocumentTextParser.readDocumentTextPayload(bytes)

        assertEquals("/EmbeddedDocumentText", payload.sourceName)
        assertEquals("Note", payload.text)
        assertTrue(payload.bytes.startsWith("SsmgV.01".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun embeddedDocumentTextDropsImplausibleNoiseLines() {
        val embedded = mutableListOf<Byte>()
        embedded.addAll("SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        embedded.add(0x00)
        embedded.add(0x1f)
        embedded.addAll(
            utf16Units("Note\u0100\u0301\u0101\u0101\u0101\u0101\u852D\uF706\r本文")
                .flatMap { listOf(((it shr 8) and 0xFF).toByte(), (it and 0xFF).toByte()) },
        )

        val bytes = cfbWithStream("/JSSlipObject1", embedded.toByteArray())

        val payload = DocumentTextParser.readDocumentTextPayload(bytes)

        assertEquals("本文", payload.text)
    }

    // ---- 単一セグメント raw 型（w[9]==1） ----

    @Test
    fun parsesRawTextSegmentFormatWithoutParagraphMarkers() {
        val textContent = utf16Units("te\nsto\nて")
        val bytes = mutableListOf<Byte>()
        extendUnits(bytes, listOf(0x5373, 0x6D67, 0x562E, 0x3031, 0x0000, 0x0001, 0x0000, 0x0100, 0x0000, 0x0001))
        extendUnits(bytes, TEXTV01_NAME_WORDS)
        extendUnits(bytes, listOf(0x0000, textContent.size))
        extendUnits(bytes, textContent)

        val parsed = DocumentTextParser.parseDocumentText(bytes.toByteArray())
        val text = parsed.plainText()
        assertTrue(text.contains("te"), "should contain 'te' but got: $text")
        assertTrue(text.contains("sto"), "should contain 'sto' but got: $text")
        assertTrue(text.contains('て'), "should contain 'て' but got: $text")
    }

    // ---- マーカーレス raw 本文（w[9] > 1: 複数セグメント型） ----

    @Test
    fun markerlessRawBodyRecoversTextWhenSegmentCountExceedsOne() {
        val text = "$GALAXY_P1\n$GALAXY_P2"
        val payload = markerlessRawPayload(0x0004, text, emptyList())

        val parsed = DocumentTextParser.parseDocumentText(payload)

        assertEquals(text, parsed.plainText(), "マーカーレス raw 本文（銀河鉄道の夜）が復元できない")
    }

    @Test
    fun markerlessRawBodyIgnoresStrayRunMarkerBeyondTextSpan() {
        // span 終端を過ぎた余白の孤立 0x001f に惑わされないこと
        val text = "$GALAXY_P3\n$GALAXY_P4"
        val tail = listOf(0xFFFF, 0xFFFF, 0x0000, 0x000A, 0x001F, 0x3000, 0x74B0)
        val payload = markerlessRawPayload(0x000B, text, tail)

        val parsed = DocumentTextParser.parseDocumentText(payload)

        assertEquals(text, parsed.plainText(), "span 外の孤立 0x001f があっても本文全文を復元しなければならない")
    }

    // ---- 混在型プロローグ（P1 型） ----

    @Test
    fun mixedRawPrologueRecoversTextBeforeFirstRunMarker() {
        val prologue = utf16Units("$GALAXY_P5\n")
        val body = mutableListOf<Int>()
        body.addAll(RUN_RECORD_FOOTER_WORDS)
        body.addAll(utf16Units(GALAXY_P6))
        body.addAll(RUN_RECORD_FOOTER_WORDS)
        body.addAll(utf16Units(GALAXY_P7))
        val payload = mixedProloguePayload(0x0003, prologue, body)

        val parsed = DocumentTextParser.parseDocumentText(payload)
        val expected = "$GALAXY_P5\n$GALAXY_P6$GALAXY_P7"

        assertEquals(expected, parsed.plainText(), "最初の run マーカーより手前の raw 前置きが復元できない")
        assertEquals(
            DocumentTextElement.TextRun("$GALAXY_P5\n"),
            parsed.elements().firstOrNull(),
            "前置きは後続マーカー本文より前の TextRun として emit しなければならない",
        )
    }
}
