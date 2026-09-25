package com.hiyowa.tika.jtd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.metadata.TikaCoreProperties
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 開封不能コンテナの salvage 回帰テスト。
 *
 * /DocumentText を含む健全な CFB のディレクトリ連鎖 FAT エントリを EOF 超セクタへ
 * 書き換えた破損フィクスチャを合成し、POI が開封に失敗しても第 2 防衛線
 * （埋め込みスキャン）で本文が復元できることを検証する。
 * 出力契約は本文のみ・ヘッダなし。
 */
class BrokenContainerSalvageTest {

    private fun markerDocumentText(): ByteArray {
        // JtdParserTikaTest と同一土台: "SsmgV.01" + 0x001f マーカー + UTF-16BE 本文
        val bytes = mutableListOf<Byte>()
        bytes.addAll("SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        bytes.add(0x00)
        bytes.add(0x1f)
        bytes.addAll("サルベージ本文が戻ればよい\n".toByteArray(Charsets.UTF_16BE).toList())
        return bytes.toByteArray()
    }

    private fun cfbWithDocumentText(payload: ByteArray): ByteArray {
        POIFSFileSystem().use { fs ->
            fs.root.createDocument("DocumentText", ByteArrayInputStream(payload))
            val out = ByteArrayOutputStream()
            fs.writeFilesystem(out)
            return out.toByteArray()
        }
    }

    /**
     * ディレクトリ連鎖破損: FAT エントリを EOF 超セクタ番号へ書き換える。
     * （ヘッダ byte 30: セクタサイズシフト / byte 48: 最初のディレクトリセクタ /
     *   byte 76: DIFAT[0]=FAT セクタ。実ファイル診断で確定したレイアウト準拠）
     */
    private fun truncateDirectoryChain(data: ByteArray): ByteArray {
        val broken = data.copyOf()
        val sectorShift = ByteBuffer.wrap(broken, 30, 2).order(ByteOrder.LITTLE_ENDIAN).getShort().toInt()
        val sectorSize = 1 shl sectorShift
        val dirStart = ByteBuffer.wrap(broken, 48, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val fatSector = ByteBuffer.wrap(broken, 76, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertTrue(fatSector >= 0 && fatSector != -2, "想定される単一 FAT セクタレイアウト: $fatSector")
        // FAT セクタ内の dirStart 連鎖の終端マーカーを EOF 超セクタ番号へ書き換える
        val entryOffset = (fatSector + 1) * sectorSize + dirStart * 4
        ByteBuffer.wrap(broken, entryOffset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x7FFFFFFF)
        // 元の連鎖 END（0xFFFFFFFE）だった位置を EOF 超セクタ番号へ書き換えた前提
        return broken
    }

    @Test
    fun poiTolerantPreconditionTheBrokenFixtureReallyThrowsOnOpen() {
        val broken = truncateDirectoryChain(cfbWithDocumentText(markerDocumentText()))
        val failure = runCatching { JtdContainerReader.open(broken) }.exceptionOrNull()
        assertTrue(
            failure != null,
            "フィクスチャが壊れていない（POI が開けてしまう）— corruption 位置を見直すこと",
        )
    }

    @Test
    fun detectorConvergesBrokenContainerWithEmbeddedBodyToEmbeddedFormat() {
        val broken = truncateDirectoryChain(cfbWithDocumentText(markerDocumentText()))

        assertEquals(JtdFormat.COMPOUND_EMBEDDED_DOCUMENT_TEXT, JtdFormatDetector.detect(broken))
    }

    @Test
    fun readDocumentTextPayloadSalvagesBodyViaEmbeddedScanWhenPoiFails() {
        val broken = truncateDirectoryChain(cfbWithDocumentText(markerDocumentText()))

        val payload = DocumentTextParser.readDocumentTextPayload(broken)

        assertContains(payload.text, "サルベージ本文が戻ればよい")
    }

    @Test
    fun parserEndToEndSalvagesBodyWithoutHeader() {
        val broken = truncateDirectoryChain(cfbWithDocumentText(markerDocumentText()))

        val metadata = Metadata().apply {
            set(TikaCoreProperties.RESOURCE_NAME_KEY, "corrupted-cfb.jtd")
        }
        val handler = BodyContentHandler(-1)
        TikaInputStream.get(broken, metadata).use { stream ->
            JtdParser().parse(stream, handler, metadata, ParseContext())
        }

        val text = handler.toString()
        assertContains(text, "サルベージ本文が戻ればよい")
        // 本文のみ・ヘッダなし（§11.2 裁定）: ヘッダ前置きも枠注記も出ない
        assertTrue(!text.startsWith("- "), "ヘッダ前置きが出てはいけない: ${text.take(16)}")
        assertTrue(!text.contains("※枠内テキスト"), "枠注記は salvage 対象外")
    }
}
