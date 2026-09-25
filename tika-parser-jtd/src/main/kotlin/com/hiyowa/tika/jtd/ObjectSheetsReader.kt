package com.hiyowa.tika.jtd

/**
 * マルチシート文書（`/ObjectSheets`）のリーダ。移植元: OpenJTD `rjtd-core/src/sheet.rs`。
 *
 * レイアウト（RFC 0010 準拠・実データ確認済み）:
 * - `/ObjectSheets/SheetInfo`: ルートシートの名前（末尾逆向き走査で抽出）
 * - `/ObjectSheets/DocSheet/DocItemInfo`: サブシート列、UTF-16LE レコード
 *   ```text
 *   count u32LE
 *   各エントリ: [skip 4B][nameLen u32LE][UTF-16LE 名前][docsId u32LE][trailer 26B]
 *   ```
 * - `/ObjectSheets/DocSheet/DocItemInfo2`: 元の保存パス列（語幅2前向きスキャン）
 * - `/ObjectSheets/DocSheet/DOCS_%04d/DocumentText`、`.../Footnote`: サブシート本体
 *
 * CFB 開封は [JtdContainerReader] を利用する（[JtdContainerReader.readStream] は
 * 対象不在で null、I/O 上限超過等で [IOException] を送出する）。
 */
object ObjectSheetsReader {

    /** /ObjectSheets/SheetInfo の CFB フルパス（Rust `OBJECT_SHEETS_SHEET_INFO_PATH`）。 */
    const val OBJECT_SHEETS_SHEET_INFO_PATH = "/ObjectSheets/SheetInfo"

    /** /ObjectSheets/DocSheet/DocItemInfo の CFB フルパス（Rust `DOC_SHEET_DOC_ITEM_INFO_PATH`）。 */
    const val DOC_SHEET_DOC_ITEM_INFO_PATH = "/ObjectSheets/DocSheet/DocItemInfo"

    /** /ObjectSheets/DocSheet/DocItemInfo2 の CFB フルパス（Rust `DOC_SHEET_DOC_ITEM_INFO2_PATH`）。 */
    const val DOC_SHEET_DOC_ITEM_INFO2_PATH = "/ObjectSheets/DocSheet/DocItemInfo2"

    /** サブシート保存ストレージの接頭辞（Rust `DOC_SHEET_STORAGE_PREFIX`）。 */
    const val DOC_SHEET_STORAGE_PREFIX = "/ObjectSheets/DocSheet/DOCS_"

    private const val DOC_ITEM_INFO_TRAILER_LEN = 26

    /**
     * 単一シートの記述（Rust `SheetItem` / `DocumentSheetInfo`）。
     *
     * @property index 0 起算のシート番号（読み込み順）。
     * @property name シート名（ルートシートは既定値「タイトル」になりうる）。
     * @property storagePath シート本体の保存パス。空文字または "/" はルートシート
     *   （`/DocumentText` 本体・`/Footnote` を指す）。
     * @property originalPath 元の保存パス（DocItemInfo2 から復元できたら Some）。
     */
    data class SheetItem(
        val index: Int,
        val name: String,
        val storagePath: String,
        val originalPath: String?,
    ) {
        /** シート本体の DocumentText パス（Rust `SheetItem::document_text_path` と同一規則）。 */
        fun documentTextPath(): String =
            if (storagePath.isEmpty() || storagePath == "/") {
                "/DocumentText"
            } else {
                storagePath.trimEnd('/') + "/DocumentText"
            }

        /** シート本体の Footnote パス（Rust `SheetItem::footnote_path` と同一規則）。 */
        fun footnotePath(): String =
            if (storagePath.isEmpty() || storagePath == "/") {
                "/Footnote"
            } else {
                storagePath.trimEnd('/') + "/Footnote"
            }
    }

    /**
     * マルチシート文書か（Rust `has_multiple_sheets` と同一: SheetInfo か
     * DocItemInfo のいずれかが存在すれば true）。
     */
    fun hasMultipleSheets(data: ByteArray): Boolean =
        JtdContainerReader.withFileSystem(data) { fs ->
            JtdContainerReader.readStream(fs, OBJECT_SHEETS_SHEET_INFO_PATH) != null ||
                JtdContainerReader.readStream(fs, DOC_SHEET_DOC_ITEM_INFO_PATH) != null
        }

    /**
     * 文書のシート列を列挙する（Rust `read_document_sheets` と同一）。
     *
     * - SheetInfo と DocItemInfo の両方不在（/DocumentText のみの通常文書）→ 空リスト
     * - ルートシートを先頭に、DocItemInfo のサブシート列を `DOCS_%04d` 保存パス付きで追加
     *   （既定のルート名は「タイトル」）。
     *
     * @throws java.io.IOException CFB 開封・読み出し失敗（Rust の非 NotFound エラー伝播に相当）
     */
    fun readDocumentSheets(data: ByteArray): List<SheetItem> {
        return JtdContainerReader.withFileSystem(data) { fs ->
            val sheetInfoBytes = JtdContainerReader.readStream(fs, OBJECT_SHEETS_SHEET_INFO_PATH)
            val docItemInfoBytes = JtdContainerReader.readStream(fs, DOC_SHEET_DOC_ITEM_INFO_PATH)

            if (sheetInfoBytes == null && docItemInfoBytes == null) {
                emptyList()
            } else {
                val originalPaths = JtdContainerReader.readStream(fs, DOC_SHEET_DOC_ITEM_INFO2_PATH)
                    ?.let { parseDocItemInfo2(it) }
                    ?: emptyList()

                val rootName = sheetInfoBytes?.let { parseSheetInfoRootName(it) } ?: "タイトル"

                val sheets = mutableListOf(SheetItem(0, rootName, "", null))
                docItemInfoBytes?.let { bytes ->
                    parseDocItemInfo(bytes).forEachIndexed { subIndex, (name, docsId) ->
                        sheets.add(
                            SheetItem(
                                index = sheets.size,
                                name = name,
                                storagePath = DOC_SHEET_STORAGE_PREFIX + docsId.toString().padStart(4, '0'),
                                originalPath = originalPaths.getOrNull(subIndex),
                            ),
                        )
                    }
                }
                sheets
            }
        }
    }

    /**
     * SheetInfo バイト列からルートシート名を抽出する。
     * Rust `parse_sheet_info_root_name` と同一（末尾逆向き走査）。
     *
     * 末尾から 8 バイト手前まで、2 バイト刻みで後退しながら `<len u32LE><UTF-16LE>` の
     * 候補を検出する。len が 1..=64、範囲内、全ユニットが 0x0020..=0xD7FF のときのみ
     * 有効（サロゲートは不可）。該当なしは null。
     */
    fun parseSheetInfoRootName(data: ByteArray): String? {
        if (data.size < 12) {
            return null
        }
        // Rust: data.len().saturating_sub(8)（len >= 12 が保証済み）
        var offset = data.size - 8
        while (offset >= 4) {
            if (offset + 4 <= data.size) {
                val len = u32le(data, offset)
                if (len in 1L..64L) {
                    val lenInt = len.toInt() // 1..=64 で Int 範囲内に安全
                    if (offset + 4 + lenInt * 2 <= data.size) {
                        val start = offset + 4
                        val end = start + lenInt * 2
                        var valid = true
                        for (i in start until end step 2) {
                            val unit = ((data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8))
                            if (unit < 0x20 || unit >= 0xD800) {
                                valid = false
                                break
                            }
                        }
                        if (valid) {
                            val sb = StringBuilder()
                            for (i in start until end step 2) {
                                val unit = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
                                sb.append(unit.toChar())
                            }
                            return sb.toString()
                        }
                    }
                }
            }
            // Rust: offset.checked_sub(2) が None なら break
            if (offset <= 1) {
                break
            }
            offset -= 2
        }
        return null
    }

    /**
     * DocItemInfo ストリームを解析する。Rust `parse_doc_item_info` と同一構造
     * （全フィールド UTF-16LE / u32LE）。
     *
     * レコード: `count u32LE`、各エントリ
     * `[skip 4B][nameLen u32LE][UTF-16LE 名前][docsId u32LE][trailer 26B]`。
     *
     * @return (シート名, docsId) の列
     * @throws InvalidDataException 途中で打ち切られている・UTF-16 不正（Rust `Error::InvalidData`）
     */
    fun parseDocItemInfo(data: ByteArray): List<Pair<String, Long>> {
        if (data.size < 4) {
            throw InvalidDataException("DocItemInfo stream too short")
        }
        val count = u32le(data, 0)
        var offset = 4
        val results = mutableListOf<Pair<String, Long>>()

        var entryNo = 0L
        while (entryNo < count) {
            if (offset + 8 > data.size) {
                throw InvalidDataException("DocItemInfo entry header truncated")
            }
            // offset+0..4 は skip 4B（Rust では読まずに読み飛ばすフィールド）
            val nameLen = u32le(data, offset + 4)
            offset += 8

            val nameBytesLen = nameLen * 2
            if (nameBytesLen > Int.MAX_VALUE || offset + nameBytesLen + 4 > data.size) {
                throw InvalidDataException("DocItemInfo name truncated")
            }
            val name = decodeUtf16Le(data, offset, nameBytesLen.toInt())
                ?: throw InvalidDataException("DocItemInfo name invalid UTF-16")
            offset += nameBytesLen.toInt()

            val docsId = u32le(data, offset)
            offset += 4

            // Rust と同一: 最後のエントリのみの不完全 trailer は許容される
            if (offset + DOC_ITEM_INFO_TRAILER_LEN > data.size && entryNo + 1 < count) {
                throw InvalidDataException("DocItemInfo trailer truncated")
            }
            offset = minOf(offset + DOC_ITEM_INFO_TRAILER_LEN, data.size)

            results.add(name to docsId)
            entryNo++
        }

        return results
    }

    /**
     * DocItemInfo2 ストリームから元の保存パス列を抽出する。
     * Rust `parse_doc_item_info2` と同一（語幅2前向きスキャン）。
     *
     * 各位置で `<len u32LE>` になりうる値を読み、len が 1..=512 で範囲内、UTF-16LE
     * として妥当、かつ `\` または `/` または ".jtd" を含む文字列のみパスと採用する。
     * 採用できなかった位置は 2 バイトずつ進んで再試行する。
     */
    fun parseDocItemInfo2(data: ByteArray): List<String> {
        val paths = mutableListOf<String>()
        var offset = 0
        while (offset + 4 <= data.size) {
            val len = u32le(data, offset)
            if (len in 1L..512L) {
                if (offset + 4 + len * 2 <= data.size) {
                    val start = offset + 4
                    val decoded = decodeUtf16Le(data, start, (len * 2).toInt())
                    if (decoded != null && (decoded.contains('\\') || decoded.contains('/') || decoded.contains(".jtd"))) {
                        paths.add(decoded)
                        offset += 4 + len.toInt() * 2
                        continue
                    }
                }
            }
            offset += 2
        }
        return paths
    }

    // ------------------------------------------------------------------
    // 内部ヘルパ
    // ------------------------------------------------------------------

    /**
     * data[offset..offset+4) を u32 little-endian として読む（無符号値。
     * Rust `u32::from_le_bytes` と同一）。呼び出し側は範囲を確認済み。
     */
    private fun u32le(data: ByteArray, offset: Int): Long =
        (data[offset].toLong() and 0xFF) or
            ((data[offset + 1].toLong() and 0xFF) shl 8) or
            ((data[offset + 2].toLong() and 0xFF) shl 16) or
            ((data[offset + 3].toLong() and 0xFF) shl 24)

    /**
     * data[start..start+length) を UTF-16LE として厳密デコードする。Rust
     * `String::from_utf16` と同一: 孤立した高/低サロゲートは不正（null を返す）、
     * サロゲートペアは 1 文字に結合する。
     */
    private fun decodeUtf16Le(data: ByteArray, start: Int, length: Int): String? {
        if (length % 2 != 0) {
            return null
        }
        val sb = StringBuilder()
        var i = start
        val end = start + length
        while (i < end) {
            val code = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
            i += 2
            when {
                code in 0xD800..0xDBFF -> {
                    if (i + 1 >= end) {
                        return null
                    }
                    val low = (data[i].toInt() and 0xFF) or ((data[i + 1].toInt() and 0xFF) shl 8)
                    if (low !in 0xDC00..0xDFFF) {
                        return null
                    }
                    i += 2
                    val cp = 0x10000 + ((code - 0xD800) shl 10) + (low - 0xDC00)
                    sb.appendCodePoint(cp)
                }

                code in 0xDC00..0xDFFF -> return null
                else -> sb.append(code.toChar())
            }
        }
        return sb.toString()
    }
}
