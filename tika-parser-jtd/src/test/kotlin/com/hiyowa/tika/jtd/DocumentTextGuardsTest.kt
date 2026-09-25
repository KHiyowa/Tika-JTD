package com.hiyowa.tika.jtd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OpenJTD rjtd-core/src/document_text.rs のガードレール系テスト移植（Step 5 足場）。
 *
 * 混在型プロローグのリグレッションガード・孤立マーカー抑止・テンプレート inline・
 * 装飾本文・スキップ上限・エラー報告の 6 系統。
 */
class DocumentTextGuardsTest {

    companion object {
        private const val GALAXY_P8 = "「ああ行っておいで。川へははいらないでね。」"
        private const val GALAXY_P9 = "ジョバンニは窓をあけました。"
        private const val GALAXY_P10_NOISE = "銀河ステーションで、もらったんだ。"
        private const val GALAXY_P11 = "「大きな望遠鏡で銀河をよっく調べると銀河は大体何でしょう。」"
        private const val GALAXY_P12 = "ジョバンニは、ばっと胸がつめたくなり、そこら中きぃんと鳴るように思いました。"
        private const val GALAXY_P13 = "「ああ、お前さきにおあがり。あたしはまだほしくないんだから。」"

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

    private fun cfbWithStream(path: String, payload: ByteArray): ByteArray {
        org.apache.poi.poifs.filesystem.POIFSFileSystem().use { fs ->
            val parts = path.trimStart('/').split('/')
            var dir: org.apache.poi.poifs.filesystem.DirectoryEntry = fs.root
            for (i in 0 until parts.size - 1) {
                dir = dir.createDirectory(parts[i])
            }
            dir.createDocument(parts.last(), ByteArrayInputStream(payload))
            val out = ByteArrayOutputStream()
            fs.writeFilesystem(out)
            return out.toByteArray()
        }
    }

    @Test
    fun parsesDocumentTextIntoStructuredElements() {
        val bytes = mutableListOf<Byte>()
        bytes.add(0x00)
        bytes.add(0x1f)
        bytes.addAll(utf16Units("一、").flatMap { listOf(((it shr 8) and 0xFF).toByte(), (it and 0xFF).toByte()) })
        extendUnits(bytes, listOf(0x001C, 0x0001, 0x0007, 0x0000, 0x0000, 0x0003, 0x001D))
        bytes.addAll(utf16Units("午后").flatMap { listOf(((it shr 8) and 0xFF).toByte(), (it and 0xFF).toByte()) })
        extendUnits(bytes, listOf(0x001E, 0x0005, 0x0000, 0x0001, 0x001F))
        bytes.addAll(utf16Units("の授業").flatMap { listOf(((it shr 8) and 0xFF).toByte(), (it and 0xFF).toByte()) })

        val parsed = DocumentTextParser.parseDocumentText(bytes.toByteArray())

        assertEquals("一、午后の授業", parsed.plainText())
        assertEquals(4, parsed.elements().size)
        assertEquals(DocumentTextElement.TextRun("一、"), parsed.elements()[0])
        val boundary = parsed.elements()[1]
        assertTrue(boundary is DocumentTextElement.ControlBoundary && boundary.code == 0x001C, "elements[1]=$boundary")
        val inline = parsed.elements()[2]
        assertTrue(inline is DocumentTextElement.InlineText, "elements[2]=$inline")
        assertEquals(0x0003, inline.selector)
        assertEquals("午后", inline.text)
        assertEquals(DocumentTextElement.TextRun("の授業"), parsed.elements()[3])
    }

    @Test
    fun extractsTemplatePlaceholderInlineSegments() {
        val bytes = mutableListOf<Byte>()
        extendUnits(bytes, listOf(0x001C, 0x0001, 0x0007, 0x0000, 0x0000, 0x0001, 0x001D))
        bytes.addAll(utf16Units("○○○").flatMap { listOf(((it shr 8) and 0xFF).toByte(), (it and 0xFF).toByte()) })
        extendUnits(bytes, listOf(0x001E, 0x001F))
        bytes.addAll(utf16Units("賞").flatMap { listOf(((it shr 8) and 0xFF).toByte(), (it and 0xFF).toByte()) })

        assertEquals("○○○賞", DocumentTextParser.extractDocumentText(bytes.toByteArray()))
    }

    @Test
    fun skipsTemplateInstructionInlineSegments() {
        val bytes = mutableListOf<Byte>()
        extendUnits(bytes, listOf(0x001C, 0x0001, 0x0007, 0x0000, 0x0001, 0x0000, 0x001D))
        bytes.addAll(utf16Units("名前を入力してください。").flatMap { listOf(((it shr 8) and 0xFF).toByte(), (it and 0xFF).toByte()) })
        extendUnits(bytes, listOf(0x001E, 0x001F))
        bytes.addAll(utf16Units("本文").flatMap { listOf(((it shr 8) and 0xFF).toByte(), (it and 0xFF).toByte()) })

        assertEquals("本文", DocumentTextParser.extractDocumentText(bytes.toByteArray()))

        val parsed = DocumentTextParser.parseDocumentText(bytes.toByteArray())
        val skipped = parsed.elements().filterIsInstance<DocumentTextElement.SkippedInlineText>().firstOrNull()
        assertNotNull(skipped, "テンプレート指示は skipped inline text として保持されるべき")
        assertEquals(0x0000, skipped.selector)
        assertEquals("名前を入力してください。", skipped.text)
    }

    @Test
    fun doesNotConsumeUnboundedSkippedInlineSegments() {
        val bytes = mutableListOf<Byte>()
        extendUnits(bytes, listOf(0x001C, 0x0001, 0x0007, 0x0000, 0x0001, 0x0082, 0x001D))
        repeat(DocumentTextConstants.SKIPPED_INLINE_MAX_UNITS + 2) {
            bytes.add(0x30)
            bytes.add(0x42)
        }
        bytes.add(0x00)
        bytes.add(0x1E)

        val parsed = DocumentTextParser.parseDocumentText(bytes.toByteArray())

        assertTrue(
            parsed.elements().none { it is DocumentTextElement.SkippedInlineText },
            "上限超過の skipped inline を消費してはならない",
        )
        assertTrue(
            parsed.elements().any { it is DocumentTextElement.ControlBoundary && it.code == 0x001D },
            "0x001d が制御境界として残るべき",
        )
    }

    @Test
    fun extractsStyledBodyTextInsideInlineStartEndBoundaries() {
        val bytes = mutableListOf<Byte>()
        bytes.addAll(utf16Units("先生は、黒板に吊した大きな黒い星座の図の、上から下へ白くけぶった銀河帯のようなところを指しながら、みんなに問をかけました。\n").flatMap { listOf(((it shr 8) and 0xFF).toByte(), (it and 0xFF).toByte()) })
        extendUnits(bytes, listOf(0x001C, 0x0010, 0x0011, 0x0000, 0x00A3, 0x0002, 0x0002, 0xFFFF, 0x00A4, 0x0001, 0x001D, 0xFFFF, 0x0000, 0x0011, 0x0000, 0x0010, 0x001F))
        bytes.addAll(utf16Units("「ではみなさんは、そういうふうに川だと云われたり、乳の流れたあとだと云われたりしていたこのぼんやりと白いものがほんとうは何かご承知ですか。」").flatMap { listOf(((it shr 8) and 0xFF).toByte(), (it and 0xFF).toByte()) })
        extendUnits(bytes, listOf(0x000A, 0x001C, 0x0010, 0x0011, 0x0000, 0x00A3, 0x0002, 0x0002, 0xFFFF, 0x00A4, 0x0001, 0x001E))
        bytes.add(0x00)
        bytes.add(0x1F)
        bytes.addAll(utf16Units("カムパネルラが手をあげました。").flatMap { listOf(((it shr 8) and 0xFF).toByte(), (it and 0xFF).toByte()) })

        val extracted = DocumentTextParser.extractDocumentText(bytes.toByteArray())
        assertTrue(extracted.contains("「ではみなさんは、そういうふうに川だと云われたり、乳の流れたあとだと云われたりしていたこのぼんやりと白いものがほんとうは何かご承知ですか。」"))
        assertTrue(extracted.contains("カムパネルラが手をあげました。"))
    }

    @Test
    fun parsesDocumentTextIgnoresIsolatedTextMarkerInTrailingMetadata() {
        // 末尾メタデータの孤立 0x001f が run を開始せず、直後バイナリが散文化しないこと
        val bytes = mutableListOf<Byte>()
        bytes.addAll("SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        bytes.add(0x00)
        bytes.add(0x1F)
        bytes.addAll(utf16Units("ジョバンニ").flatMap { listOf(((it shr 8) and 0xFF).toByte(), (it and 0xFF).toByte()) })
        bytes.addAll(listOf(0x00, 0x0E, 0x00, 0x00))
        bytes.addAll(listOf(0x12, 0x34, 0x56, 0x78))
        bytes.add(0x00)
        bytes.add(0x1F)
        bytes.addAll(listOf(0xFE, 0x01, 0x02, 0x00, 0x03, 0x02, 0x02, 0x00, 0x03, 0xFF, 0x00, 0x00))

        val parsed = DocumentTextParser.parseDocumentText(bytes.toByteArray())
        val plain = parsed.plainText()
        assertEquals("ジョバンニ", plain.trim())
        assertTrue(
            !plain.contains('\uFE01') && !plain.contains('\u0200') && !plain.contains('\u0302') && !plain.contains('\u03FF'),
            "孤立マーカー後メタがテキスト化している: $plain",
        )
    }

    @Test
    fun mixedRawPrologueKeepsLineBreaksAndStopsAtControlBoundary() {
        val prologue = mutableListOf<Int>()
        prologue.addAll(utf16Units("$GALAXY_P8\n"))
        prologue.addAll(listOf(0x0000, 0x0000))
        prologue.addAll(utf16Units(GALAXY_P9))
        prologue.add(0x0019)
        prologue.addAll(utf16Units(GALAXY_P10_NOISE))
        val body = mutableListOf<Int>()
        body.addAll(RUN_RECORD_FOOTER_WORDS)
        body.addAll(utf16Units(GALAXY_P11))
        val payload = mixedProloguePayload(0x0003, prologue, body)

        val parsed = DocumentTextParser.parseDocumentText(payload)
        val expected = "$GALAXY_P8\n$GALAXY_P9$GALAXY_P11"

        assertEquals(expected, parsed.plainText(), "改行保持・ゼロパディングスキップ・制御境界打ち切りが守られていない")
        assertTrue(!parsed.plainText().contains(GALAXY_P10_NOISE), "制御境界 0x0019 より後ろのノイズ語を出力してはならない")
    }

    @Test
    fun mixedRawPrologueWhitespaceOnlyDoesNotFire() {
        val prologue = listOf(0x000A, 0x000A)
        val body = mutableListOf<Int>()
        body.addAll(RUN_RECORD_FOOTER_WORDS)
        body.addAll(utf16Units(GALAXY_P12))
        val payload = mixedProloguePayload(0x0003, prologue, body)

        val parsed = DocumentTextParser.parseDocumentText(payload)

        assertEquals(GALAXY_P12, parsed.plainText(), "空白のみの前置きでプロローグを発火させ出力を変えてはならない")
    }

    @Test
    fun mixedRawPrologueEmptySpanKeepsOutputUnchanged() {
        val body = mutableListOf<Int>()
        body.addAll(RUN_RECORD_FOOTER_WORDS)
        body.addAll(utf16Units(GALAXY_P12))
        body.addAll(RUN_RECORD_FOOTER_WORDS)
        body.addAll(utf16Units(GALAXY_P13))
        val payload = mixedProloguePayload(0x0003, emptyList(), body)

        val parsed = DocumentTextParser.parseDocumentText(payload)
        val expected = GALAXY_P12 + GALAXY_P13

        assertEquals(expected, parsed.plainText(), "前置き空のマーカー型で出力が変化してはならない（リグレッション）")
    }

    @Test
    fun reportsCompressedDocumentWhenDocumentTextIsAbsent() {
        val bytes = cfbWithStream("/JSCompDocument", byteArrayOf(0x26, 0x00) + "JustCompressedDocument".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x00) + "-lh5-".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0x00) + "payload".toByteArray(Charsets.ISO_8859_1))

        val ex = assertFailsWith<JtdException> { DocumentTextParser.readDocumentTextPayload(bytes) }
        assertTrue(ex.message!!.contains("invalid data"), "actual: ${ex.message}")
    }

    @Test
    fun reportsMissingDocumentTextWithoutKnownCompressedPayload() {
        val bytes = cfbWithStream("/Other", "payload".toByteArray(Charsets.ISO_8859_1))

        val ex = assertFailsWith<NotFoundException> { DocumentTextParser.readDocumentTextPayload(bytes) }
        assertTrue(ex.message!!.contains("not found"), "actual: ${ex.message}")
    }
}
