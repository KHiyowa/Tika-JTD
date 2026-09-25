package com.hiyowa.tika.jtd

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.apache.poi.poifs.filesystem.DirectoryEntry
import org.apache.poi.poifs.filesystem.POIFSFileSystem
import org.apache.tika.io.TikaInputStream
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler

/**
 * /LayoutBoxText の可変ピッチ・長 span ブロック復元テスト。
 *
 * span 長が 122 語（[LayoutBoxTextReader.LAYOUT_BOX_MAX_SPAN_WORDS]）を超えると
 * 枠テキスト全体が読めなくなるレイアウト（128 語ピッチの複数スロット跨ぎ）を、
 * 青空文庫「銀河鉄道の夜」（宮沢賢治・パブリックドメイン）の文面で再現する。
 *
 * テスト本文は testdata/ginga_tetsudo_no_yoru.txt と同一の文面を直書きした
 * （実行時ファイル読み出しをしない。フィクスチャの源テキストは同ファイルに限る）。
 */
class LayoutBoxTextWideSpanTest {

    companion object {
        private const val BLOCK_PITCH_WORDS = 128
        private val TEXT_SEGMENT_NAME = listOf(0x5465, 0x7874, 0x562E, 0x3031) // "TextV.01"

        // span 末尾〜次ブロック間のパディングに観測される非ゼロのレコード尾部語。
        private val TAIL_RECORD_WORDS = listOf(0x2500, 0x0501, 0x01FF, 0x0003, 0xFDE5, 0x0100)

        private val TAIL_TABLE_WORDS = listOf(
            0x0000, 0x0063, 0x0000, 0x0001, 0x0000, 0x0001,
            0x0000, 0x0000, 0x0000, 0x0049, 0x0000, 0x0001,
        )
    }

    // 「ではみなさんは、…」銀河帯を指す先生の問い（131 語・2 スロット跨ぎ span）。
    private val wideBoxText1 =
        "「ではみなさんは、そういうふうに川だと云われたり、乳の流れたあとだと云われたりしていたこのぼんやりと白いものがほんとうは" +
        "何かご承知ですか。」先生は、黒板に吊した大きな黒い星座の図の、上から下へ白くけぶった銀河帯のようなところを指しながら、" +
        "みんなに問をかけました。"

    // カムパネルラが手を挙げた場面（160 語）。
    private val boxTextPara2 =
        "　カムパネルラが手をあげました。それから四五人手をあげました。ジョバンニも手をあげようとして、急いでそのままやめました。" +
        "たしかにあれがみんな星だと、いつか雑誌で読んだのでしたが、このごろはジョバンニはまるで毎日教室でもねむく、" +
        "本を読むひまも読む本もないので、なんだかどんなこともよくわからないという気持ちがするのでした。"

    // 先生がジョバンニを指名する短い地の文（23 語）。
    private val boxTextPara3 = "　ところが先生は早くもそれを見附けたのでした。"

    // 先生の呼びかけ（26 語・ルート本文にも使う）。
    private val callToGiovanni = "「ジョバンニさん。あなたはわかっているのでしょう。」"

    // ジョバンニが立ち上がる場面（125 語）。
    private val boxTextPara5 =
        "　ジョバンニは勢よく立ちあがりましたが、立って見るともうはっきりとそれを答えることができないのでした。" +
        "ザネリが前の席からふりかえって、ジョバンニを見てくすっとわらいました。ジョバンニはもうどぎまぎしてまっ赤になってしまいました。" +
        "先生がまた云いました。"

    // 望遠鏡の質問（30 語・1 スロット短 span）。
    private val shortBoxText = "「大きな望遠鏡で銀河をよっく調べると銀河は大体何でしょう。」"

    // 段落連結 span（337 語・3 スロット跨ぎ）。連結区切りは改行 1 文字。
    private val wideBoxText2 = boxTextPara2 + "\n" + boxTextPara3 + "\n" + callToGiovanni + "\n" + boxTextPara5

    /** 実原本同型の可変ピッチ /LayoutBoxText ペイロードを合成する。 */
    private fun wideSpanPayload(texts: List<String>): ByteArray {
        val units = mutableListOf<Int>()
        units.addAll(listOf(0x5373, 0x6D67, 0x562E, 0x3031, 0x0000, 0x0016, 0x0000, 0x0100, 0x0000, 0x0000))
        var cursor = 10
        for (text in texts) {
            val span = text.map { it.code }
            units.addAll(TEXT_SEGMENT_NAME)
            units.add(0x0000)
            units.add(span.size)
            units.addAll(span)
            units.addAll(TAIL_RECORD_WORDS)
            cursor += TEXT_SEGMENT_NAME.size + 2 + span.size + TAIL_RECORD_WORDS.size
            val nextBoundary = 10 + ((cursor - 10 + BLOCK_PITCH_WORDS - 1) / BLOCK_PITCH_WORDS) * BLOCK_PITCH_WORDS
            units.addAll(List(nextBoundary - cursor) { 0 })
            cursor = nextBoundary
        }
        units.addAll(TAIL_TABLE_WORDS)
        units[9] = (cursor - 10) / BLOCK_PITCH_WORDS
        val bytes = ByteArray(units.size * 2)
        for (i in units.indices) {
            bytes[i * 2] = ((units[i] shr 8) and 0xFF).toByte()
            bytes[i * 2 + 1] = (units[i] and 0xFF).toByte()
        }
        return bytes
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

    private fun bodyUnits(text: String): ByteArray {
        val bytes = mutableListOf<Byte>()
        bytes.addAll("SsmgV.01".toByteArray(Charsets.ISO_8859_1).toList())
        bytes.add(0x00)
        bytes.add(0x1F)
        bytes.addAll(text.toByteArray(Charsets.UTF_16BE).toList())
        return bytes.toByteArray()
    }

    private fun parseThroughParser(data: ByteArray): String {
        val handler = BodyContentHandler(-1)
        TikaInputStream.get(ByteArrayInputStream(data)).use { stream ->
            JtdParser().parse(stream, handler, Metadata(), ParseContext())
        }
        return handler.toString()
    }

    @Test
    fun parsesVariablePitchWideSpanBlocksSequentially() {
        val payload = wideSpanPayload(listOf(wideBoxText1, shortBoxText, wideBoxText2))

        val text = assertNotNull(
            LayoutBoxTextReader.parseLayoutBoxText(payload),
            "RED: span 宣言長 > LAYOUT_BOX_MAX_SPAN_WORDS(122) の可変ピッチブロックで " +
                "parseLayoutBoxText が null を返し枠テキストが全滅している",
        )

        assertEquals(3, text.blocks.size, "3 ブロックを逐次復元できること")
        assertEquals(wideBoxText1, text.blocks[0])
        assertEquals(shortBoxText, text.blocks[1])
        assertEquals(wideBoxText2, text.blocks[2])

        // span 宣言長より後ろのレコード尾部／末尾テーブル語をテキストに混入しないこと
        val joined = text.text()
        assertTrue(!joined.contains('\u2500'), "パディングの 0x2500 がテキストに混入")
        assertTrue(!joined.contains('\u0100'), "パディングの 0x0100 がテキストに混入")
        assertTrue(!joined.contains('c'), "末尾テーブル語（0x0063）がテキストに混入")
        assertTrue(!joined.contains('I'), "末尾テーブル語（0x0049）がテキストに混入")
    }

    @Test
    fun jtdParserEmitsWideSpanBoxTextWithMarker() {
        val data = cfbWithStreams(
            mapOf(
                "/DocumentText" to bodyUnits(callToGiovanni),
                "/LayoutBoxText" to wideSpanPayload(listOf(wideBoxText1, shortBoxText, wideBoxText2)),
            ),
        )

        val out = parseThroughParser(data)
        assertTrue(out.startsWith(callToGiovanni), "実際の出力冒頭: $out")
        assertTrue(out.contains("\n※枠内テキスト\n"), "RED: 枠テキスト全滅で出力に枠注記が出ない: $out")
        assertTrue(out.contains(wideBoxText1), "RED: 長 span 枠テキストが復元されない: $out")
    }

    @Test
    fun externalSampleWideSpanLayoutIsExtracted() {
        val sample = System.getenv("JTD_SAMPLE_FILE")
        if (sample.isNullOrEmpty()) return
        val path = Paths.get(sample)
        if (!Files.exists(path)) return

        val out = parseThroughParser(Files.readAllBytes(path))
        val bodyChars = out.replace(Regex("[\\s-]"), "").length
        assertTrue(out.contains("※枠内テキスト"), "RED: 枠注記が出ない（枠テキスト喪失）")
        assertTrue(Regex("[\\u3040-\\u30ff\\u4e00-\\u9faf]").containsMatchIn(out), "RED: 和文本文が復元されない")
        assertTrue(bodyChars >= 1000, "RED: 本文量が基準に遠く及ばない（枠テキスト喪失の兆候）: $bodyChars 字")
    }
}
