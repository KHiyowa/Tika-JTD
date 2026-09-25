package com.hiyowa.tika.jtd

/**
 * JustCompressedDocument ストリーム (`/JSCompDocument`) の先頭マジック。
 * `0x26 0x00` + "JustCompressedDocument"（ASCII）。
 */
private val JUST_COMPRESSED_MAGIC: ByteArray =
    byteArrayOf(0x26, 0x00) + "JustCompressedDocument".toByteArray(Charsets.ISO_8859_1)

private val CFB_MAGIC_BYTES: ByteArray = JtdContainerReader.CFB_MAGIC

/**
 * JTD 形式の 5 段階判定。OpenJTD (rjtd-core/src/format.rs) の判定制御を移植したもの。
 */
object JtdFormatDetector {

    /**
     * バイト列から JTD 形式を判定する。
     *
     * 判定順序（format.rs と同一）:
     * 1. CFB マジック非一致 -> [JtdFormat.UNKNOWN]
     * 2. `/DocumentText` が読める -> [JtdFormat.COMPOUND_DOCUMENT_TEXT]
     * 3. `/JSCompDocument` が JustCompressedDocument マジック -> [JtdFormat.COMPOUND_JUST_COMPRESSED_DOCUMENT]
     * 4. [DocumentTextParser.hasEmbeddedDocumentText]（SsmgV.01 埋め込み候補が
     *    存在）-> [JtdFormat.COMPOUND_EMBEDDED_DOCUMENT_TEXT]
     * 5. その他 CFB -> [JtdFormat.COMPOUND_UNKNOWN]
     *
     * POI の開封失敗・読み出し失敗（エントリ不在を除く）は §2.3 第 2 防衛線の
     * 埋め込みスキャン判定へフォールバックし、[DocumentTextParser.hasEmbeddedDocumentText]
     * がヒットすれば [JtdFormat.COMPOUND_EMBEDDED_DOCUMENT_TEXT] に、
     * ヒットしなければ [JtdFormat.COMPOUND_UNKNOWN] へ収束させる。
     *
     * 実測（POI 5.5.1）: 破損 CFB では `POIFSFileSystem` コンストラクタが
     * PropertyTable ディレクトリ走査中 `IndexOutOfBoundsException`（"Block N not found"）を
     * 投げるため、`IOException` キャッチでは拾えず、例外型の広い `catch (Exception)` で扱う。
     */
    fun detect(data: ByteArray): JtdFormat {
        if (!data.startsWith(CFB_MAGIC_BYTES)) return JtdFormat.UNKNOWN

        return try {
            JtdContainerReader.withFileSystem(data) { fs ->
                when {
                    JtdContainerReader.readStream(fs, "/DocumentText") != null -> JtdFormat.COMPOUND_DOCUMENT_TEXT
                    else -> {
                        val jsComp = JtdContainerReader.readStream(fs, "/JSCompDocument")
                        if (jsComp != null && jsComp.startsWith(JUST_COMPRESSED_MAGIC)) {
                            JtdFormat.COMPOUND_JUST_COMPRESSED_DOCUMENT
                        } else if (DocumentTextParser.hasEmbeddedDocumentText(data)) {
                            // Step4: SsmgV.01 埋め込み候補の有無で判定（検出はヒット有無のみ。
                            // 完全な埋め込み解析は readDocumentTextPayload が担当するため、
                            // ここでは軽量な候補チェックで全体の parse を避ける）。
                            JtdFormat.COMPOUND_EMBEDDED_DOCUMENT_TEXT
                        } else {
                            JtdFormat.COMPOUND_UNKNOWN
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // POI 開封失敗（破損 CFB 等）・上限超過等の読み出し失敗。
            // POI 5.5.1 実測: 破損 CFB では POIFSFileSystem コンストラクタ（PropertyTable
            // ディレクトリ走査）が IndexOutOfBoundsException（"Block N not found"）を投げ、
            // IOException キャッチでは拾えないため、ここでは型に関係なく拾う。
            // §2.3 第 2 防衛線: まず SsmgV.01 埋め込み候補を確認し、ヒットなら
            // COMPOUND_EMBEDDED_DOCUMENT_TEXT、なければ従来どおり COMPOUND_UNKNOWN。
            if (DocumentTextParser.hasEmbeddedDocumentText(data)) {
                JtdFormat.COMPOUND_EMBEDDED_DOCUMENT_TEXT
            } else {
                JtdFormat.COMPOUND_UNKNOWN
            }
        }
    }
}
