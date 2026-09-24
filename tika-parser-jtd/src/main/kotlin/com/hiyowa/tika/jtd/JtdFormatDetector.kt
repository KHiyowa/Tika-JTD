package com.hiyowa.tika.jtd

import java.io.IOException

/**
 * JustCompressedDocument ストリーム (`/JSCompDocument`) の先頭マジック。
 * `0x26 0x00` + "JustCompressedDocument"（ASCII）。
 */
private val JUST_COMPRESSED_MAGIC: ByteArray =
    byteArrayOf(0x26, 0x00) + "JustCompressedDocument".toByteArray(Charsets.ISO_8859_1)

/**
 * 埋め込み DocumentText 検索用のマーカー。
 */
private val SSMG_MARKER: ByteArray = "SsmgV.01".toByteArray(Charsets.ISO_8859_1)

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
     * 4. バイト列全体に `SsmgV.01` を内蔵 -> [JtdFormat.COMPOUND_EMBEDDED_DOCUMENT_TEXT]
     * 5. その他 CFB -> [JtdFormat.COMPOUND_UNKNOWN]
     *
     * POI の開封失敗・読み出し失敗（エントリ不在を除く）は [JtdFormat.COMPOUND_UNKNOWN] へ収束させる。
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
                        } else if (data.containsBytes(SSMG_MARKER)) {
                            // TODO(Step4): Step1 の簡易版（全体走査で `SsmgV.01` を探すだけ）。
                            // 後続の Step4 で完全に尤度フィルタへ置換予定。
                            JtdFormat.COMPOUND_EMBEDDED_DOCUMENT_TEXT
                        } else {
                            JtdFormat.COMPOUND_UNKNOWN
                        }
                    }
                }
            }
        } catch (e: IOException) {
            // POI 開封失敗（破損 CFB 等）・上限超過等の読み出し失敗
            JtdFormat.COMPOUND_UNKNOWN
        }
    }
}

/** バイト列の先頭が [prefix] か。引数が空なら常に真。 */
private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

/** バイト列 [needle] を [haystack] の中で探す（単純逐次比較）。 */
private fun ByteArray.containsBytes(needle: ByteArray): Boolean {
    if (needle.isEmpty()) return true
    if (size < needle.size) return false
    @Suppress("LoopWithTooManyJumpStatements")
    outer@ for (i in 0..size - needle.size) {
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) continue@outer
        }
        return true
    }
    return false
}
