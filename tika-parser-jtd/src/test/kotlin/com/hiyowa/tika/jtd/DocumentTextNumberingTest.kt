package com.hiyowa.tika.jtd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * OpenJTD rjtd-core/src/document_text.rs の自動番号復元と末尾露出制御コード
 * トリムのテスト移植（Step 5）。本文は銀河鉄道の夜（青空文庫、パブリックドメイン）。
 */
class DocumentTextNumberingTest {

    private fun extendUnits(bytes: MutableList<Byte>, units: List<Int>) {
        for (unit in units) {
            bytes.add(((unit shr 8) and 0xFF).toByte())
            bytes.add((unit and 0xFF).toByte())
        }
    }

    private fun utf16Units(text: String): List<Int> = text.map { it.code }

    /** u32 を 4 バイト big-endian 生バイトとして直接追記（Rust の to_be_bytes と同一）。 */
    private fun appendU32Be(bytes: MutableList<Byte>, value: Int) {
        bytes.add(((value shr 24) and 0xFF).toByte())
        bytes.add(((value shr 16) and 0xFF).toByte())
        bytes.add(((value shr 8) and 0xFF).toByte())
        bytes.add((value and 0xFF).toByte())
    }

    @Test
    fun restoresHeadingAndItemizationNumberingFromParagraphHeaders() {
        val content = mutableListOf<Int>()

        // Heading Level 1 (章): 午后の授業
        content.addAll(listOf(0x001C, 0x0010, 0x0011, 0x0000, 0x00A3, 0x0002, 0x0001, 0xFFFF, 0x0050, 0x0002, 0x0001, 0xFFFF, 0x0000, 0x0011, 0x0000, 0x0010, 0x001F))
        content.addAll(utf16Units("午后の授業\n"))

        // Heading Level 2 (節): 星座の図
        content.addAll(listOf(0x001C, 0x0010, 0x0011, 0x0000, 0x00A3, 0x0002, 0x0001, 0xFFFF, 0x0050, 0x0002, 0x0002, 0xFFFF, 0x0000, 0x0011, 0x0000, 0x0010, 0x001F))
        content.addAll(utf16Units("星座の図\n"))

        // Heading Level 3 (項): 銀河の巨きな星
        content.addAll(listOf(0x001C, 0x0010, 0x0011, 0x0000, 0x00A3, 0x0002, 0x0001, 0xFFFF, 0x0050, 0x0002, 0x0003, 0xFFFF, 0x0000, 0x0011, 0x0000, 0x0010, 0x001F))
        content.addAll(utf16Units("銀河の巨きな星\n"))

        // 箇条書き Style 2, 項目1 (restart)
        content.addAll(listOf(0x001C, 0x0010, 0x0011, 0x0000, 0x00A3, 0x0002, 0x0002, 0x7FFF, 0x00A4, 0x0001, 0x001D, 0xFFFF, 0x0000, 0x0011, 0x0000, 0x0010, 0x001F))
        content.addAll(utf16Units("ジョバンニは手をあげようとして、急いでそれをやめました。\n"))

        // 箇条書き Style 2, 項目2
        content.addAll(listOf(0x001C, 0x0010, 0x0011, 0x0000, 0x00A3, 0x0002, 0x0002, 0xFFFF, 0x00A4, 0x0001, 0x001D, 0xFFFF, 0x0000, 0x0011, 0x0000, 0x0010, 0x001F))
        content.addAll(utf16Units("カムパネルラが手をあげました。\n"))

        // 丸数字 Style 8: level 1
        content.addAll(listOf(0x001C, 0x0010, 0x0011, 0x0000, 0x00A3, 0x0002, 0x0008, 0xFFFF, 0x0050, 0x0002, 0x0001, 0xFFFF, 0x0000, 0x0011, 0x0000, 0x0010, 0x001F))
        content.addAll(utf16Units("カムパネルラ\n"))

        // 丸数字 Style 8: level 2
        content.addAll(listOf(0x001C, 0x0010, 0x0011, 0x0000, 0x00A3, 0x0002, 0x0008, 0xFFFF, 0x0050, 0x0002, 0x0002, 0xFFFF, 0x0000, 0x0011, 0x0000, 0x0010, 0x001F))
        content.addAll(utf16Units("ジョバンニ\n"))

        val bytes = mutableListOf<Byte>()
        bytes.addAll("SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        extendUnits(bytes, listOf(0x0000, 0x0001, 0x0000, 0x0100, 0x0000, 0x0027))
        bytes.addAll("TextV.01".toByteArray(Charsets.ISO_8859_1).toList())
        appendU32Be(bytes, content.size)
        extendUnits(bytes, content)

        val plain = DocumentTextParser.parseDocumentText(bytes.toByteArray()).plainText()

        assertTrue(plain.contains("第1章 午后の授業"), "expected chapter 1 prefix, got: $plain")
        assertTrue(plain.contains("1.1 星座の図"), "expected section 1.1 prefix, got: $plain")
        assertTrue(plain.contains("1.1.1 銀河の巨きな星"), "expected subsection 1.1.1 prefix, got: $plain")
        assertTrue(plain.contains("(1) ジョバンニは手をあげようとして、急いでそれをやめました。"), "expected itemization (1) prefix, got: $plain")
        assertTrue(plain.contains("(2) カムパネルラが手をあげました。"), "expected itemization (2) prefix, got: $plain")
        assertTrue(plain.contains("① カムパネルラ"), "expected circled number ① prefix, got: $plain")
        assertTrue(plain.contains("①-1 ジョバンニ"), "expected circled number ①-1 prefix, got: $plain")
    }

    @Test
    fun trimsTrailingExposedTerminalControlsFromPlainText() {
        val body = "ではみなさんは、そういうふうに川だと云われたり、乳の流れたあとだと云われたりしていたこのぼんやりと白いものがほんとうは何かご承知ですか。"

        assertEquals("ジョバンニ", DocumentTextParser.trimTrailingExposedControls("ジョバンニ\uFE14\u0400\u0490"))
        assertEquals("ジョバンニ", DocumentTextParser.trimTrailingExposedControls("ジョバンニ\uFE14"))
        assertEquals("ジョバンニ", DocumentTextParser.trimTrailingExposedControls("ジョバンニ\u0490"))
        // 直前の空白も一緒にトリム（"0.475 ︔" 型）
        assertEquals("0.475", DocumentTextParser.trimTrailingExposedControls("0.475 \uFE14\u0490"))
        // 制御コードの後ろの空白も末尾領域に含まれる
        assertEquals("ジョバンニ", DocumentTextParser.trimTrailingExposedControls("ジョバンニ\uFE14  "))
        // 末尾空白のみの連続は正当な行終端として保持
        assertEquals("ジョバンニ\n\n", DocumentTextParser.trimTrailingExposedControls("ジョバンニ\n\n"))
        // 露出コードが無ければ完全一致
        assertEquals(body, DocumentTextParser.trimTrailingExposedControls(body))

        // parse 統合: コンテンツ末尾の露出終端制御（0xFE14/0x0400/0x0490）が消える
        val contentUnits = utf16Units(body) + listOf(0xFE14, 0x0400, 0x0490)
        val bytes = mutableListOf<Byte>()
        bytes.addAll("SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        extendUnits(bytes, listOf(0x0000, 0x0001, 0x0000, 0x0100, 0x0000, 0x0027))
        bytes.addAll("TextV.01".toByteArray(Charsets.ISO_8859_1).toList())
        appendU32Be(bytes, 1 + contentUnits.size)
        bytes.add(0x00)
        bytes.add(0x1F)
        extendUnits(bytes, contentUnits)

        val plain = DocumentTextParser.parseDocumentText(bytes.toByteArray()).plainText()
        assertTrue(plain.endsWith(body), "expected clean trailing body text, got: $plain")
        assertFalse(plain.contains('\uFE14'), "exposed terminal controls should be trimmed")
        assertFalse(plain.contains('\u0490'), "exposed terminal controls should be trimmed")
    }

    @Test
    fun keepsMidTextExposedRecordsButOnlyTrimsTheFinalSequence() {
        val bytes = mutableListOf<Byte>()
        bytes.addAll("SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        bytes.add(0x00)
        bytes.add(0x1F)
        bytes.addAll(utf16Units("銀河").flatMap { listOf(((it shr 8) and 0xFF).toByte(), (it and 0xFF).toByte()) })
        bytes.addAll(listOf(0x04, 0x00))
        bytes.addAll(utf16Units("鉄道").flatMap { listOf(((it shr 8) and 0xFF).toByte(), (it and 0xFF).toByte()) })
        bytes.addAll(listOf(0xFE, 0x14, 0x04, 0x90))

        val plain = DocumentTextParser.parseDocumentText(bytes.toByteArray()).plainText()
        assertEquals("銀河\u0400鉄道", plain)
    }
}
