package com.hiyowa.tika.jtd

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * OpenJTD rjtd-core/src/document_text.rs の inline テスト移植（Step 2a）。
 *
 * 対象: マーカー走査本体（0x001f 開始・制御境界・run 継続・インライン/ルビ skip）。
 * map_document_text（位置ミラー）のテストは Step 2b に分離する。
 * 自動番号・露出コードトリムは Step 5 で追加移植。
 */
class DocumentTextParserTest {

    /** テストヘルパ: UTF-16BE 語列をバイト列に追記する（Rust の extend_units 相当）。 */
    private fun extendUnits(bytes: MutableList<Byte>, units: List<Int>) {
        for (unit in units) {
            bytes.add(((unit shr 8) and 0xFF).toByte())
            bytes.add((unit and 0xFF).toByte())
        }
    }

    private fun utf16be(text: String): List<Byte> =
        text.toByteArray(Charsets.UTF_16BE).toList()

    @Test
    fun extractsUtf16beRunsAfterTextMarker() {
        val bytes = mutableListOf<Byte>()
        bytes.addAll("SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        bytes.addAll(listOf(0x00, 0x1f))
        bytes.addAll(utf16be("銀河"))
        bytes.addAll(listOf(0x00, 0x1c, 0x00, 0x1f))
        bytes.addAll(utf16be("鉄道\n"))

        assertEquals("銀河鉄道\n", DocumentTextParser.extractDocumentText(bytes.toByteArray()))
    }

    @Test
    fun ignoresBytesBeforeTextMarker() {
        val bytes = mutableListOf<Byte>()
        bytes.addAll(listOf(0x53, 0x73, 0x6d, 0x67, 0x00, 0x10, 0x00, 0x20))
        bytes.addAll(listOf(0x00, 0x1f))
        bytes.addAll(utf16be("本文"))

        assertEquals("本文", DocumentTextParser.extractDocumentText(bytes.toByteArray()))
    }

    @Test
    fun treatsC1ControlCodesAsBoundaries() {
        val bytes = mutableListOf<Byte>()
        bytes.addAll(listOf(0x00, 0x1f))
        bytes.addAll(utf16be("目次"))
        bytes.addAll(listOf(0x00, 0x90))
        bytes.addAll(utf16be("ignored"))

        assertEquals("目次", DocumentTextParser.extractDocumentText(bytes.toByteArray()))
    }

    @Test
    fun continuesTextAfterRowDelimiterInsideTextRun() {
        val bytes = mutableListOf<Byte>()
        bytes.addAll(listOf(0x00, 0x1f, 0x00, 0x0e))
        bytes.addAll(utf16be("１，次の計算をしなさい\n"))
        extendUnits(bytes, listOf(0x001c))

        assertEquals(
            "１，次の計算をしなさい\n",
            DocumentTextParser.extractDocumentText(bytes.toByteArray()),
        )
        // map エントリの kind/code 照合は Step 2b で移植
    }

    @Test
    fun continuesTextAfterPageBreakControlInsideTextRun() {
        val bytes = mutableListOf<Byte>()
        bytes.addAll(listOf(0x00, 0x1f))
        bytes.addAll(utf16be("一、午后の授業"))
        extendUnits(bytes, listOf(DocumentTextConstants.DOCUMENT_TEXT_PAGE_BREAK_CONTROL))
        bytes.addAll(utf16be("二、活版所\n"))
        extendUnits(bytes, listOf(0x001c))

        assertEquals(
            "一、午后の授業二、活版所\n",
            DocumentTextParser.extractDocumentText(bytes.toByteArray()),
        )
    }

    @Test
    fun continuesTextAfterInlineSpaceControlInsideTextRun() {
        val bytes = mutableListOf<Byte>()
        bytes.addAll(listOf(0x00, 0x1f))
        bytes.addAll(utf16be("ジョバンニは学校の門を"))
        extendUnits(bytes, listOf(DocumentTextConstants.DOCUMENT_TEXT_INLINE_SPACE_CONTROL))
        bytes.addAll(utf16be("出るとき\n"))
        extendUnits(bytes, listOf(0x001c))

        assertEquals(
            "ジョバンニは学校の門を出るとき\n",
            DocumentTextParser.extractDocumentText(bytes.toByteArray()),
        )
    }

    @Test
    fun extractsDisplayInlineSegmentsWithoutPhoneticAnnotations() {
        val bytes = mutableListOf<Byte>()
        bytes.addAll(listOf(0x00, 0x1f))
        bytes.addAll(utf16be("一、"))
        extendUnits(bytes, listOf(0x001c, 0x0001, 0x0007, 0x0000, 0x0000, 0x0003, 0x001d))
        bytes.addAll(utf16be("午后"))
        extendUnits(bytes, listOf(0x001e, 0x0005, 0x0000, 0x0001, 0x001f))
        extendUnits(bytes, listOf(0x001c, 0x0001, 0x0007, 0x0000, 0x0001, 0x0082, 0x001d))
        bytes.addAll(utf16be("ごご"))
        extendUnits(bytes, listOf(0x001e, 0x0005, 0x0000, 0x0001, 0x001f))
        bytes.addAll(utf16be("の授業"))

        assertEquals("一、午后の授業", DocumentTextParser.extractDocumentText(bytes.toByteArray()))

        val parsed = DocumentTextParser.parseDocumentText(bytes.toByteArray())
        val skipped = parsed.elements()
            .filterIsInstance<DocumentTextElement.SkippedInlineText>()
            .firstOrNull()
        assertNotNull(skipped, "ルビ注記は skipped inline text として保持されるべき")
        assertEquals(0x0082, skipped.selector)
        assertEquals("ごご", skipped.text)
        assertTrue(skipped.rawBytes.isNotEmpty())
    }
}
