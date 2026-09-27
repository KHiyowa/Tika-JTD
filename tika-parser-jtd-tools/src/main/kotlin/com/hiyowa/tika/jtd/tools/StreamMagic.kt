package com.hiyowa.tika.jtd.tools

/**
 * OLE2 ストリーム先頭のマジック識別。
 *
 * 判定は先頭512バイトの窓内で行い、ストリーム本文は一切返さない（PII 防止）。
 *
 * @property label 識別ラベル（"PNG" / "JPEG" / "WMF" / "EMF" / "MATH.VAF" / "JSSnapShot32" /
 *                 "METAFILE" / "PDF" / "GIF" / "EMPTY" / "UNKNOWN"）
 * @property offset マジックが出現したバイト位置（窓内先頭検査時は 0）
 */
data class Detection(val label: String, val offset: Long)

object StreamMagic {

    /** 判定制限（先頭512バイト）。 */
    private const val WINDOW = 512

    private val MATH_VAF_UTF16 = "MATH.VAF".toByteArray(Charsets.UTF_16LE)
    private val JS_SNAPSHOT = "JSSnapShot32".toByteArray(Charsets.US_ASCII)
    private val METAFILE = "METAFILE".toByteArray(Charsets.US_ASCII)
    private val WMF_LE = byteArrayOf(0x01, 0x00, 0x09, 0x00)
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val GIF = "GIF8".toByteArray(Charsets.US_ASCII)
    private val PDF = "%PDF".toByteArray(Charsets.US_ASCII)

    /**
     * [header] を識別する。
     *
     * 優先順: 空 → MATH.VAF(UTF-16LE) → JSSnapShot32 → METAFILE → WMF(先頭) → EMF →
     * 窓内 PNG → JPEG → GIF → PDF → 窓内 WMF → UNKNOWN。
     */
    fun detect(header: ByteArray): Detection {
        if (header.isEmpty()) return Detection("EMPTY", 0)
        if (startsWith(header, MATH_VAF_UTF16)) return Detection("MATH.VAF", 0)
        if (startsWith(header, JS_SNAPSHOT)) return Detection("JSSnapShot32", 0)
        if (startsWith(header, METAFILE)) return Detection("METAFILE", 0)
        if (startsWith(header, WMF_LE)) return Detection("WMF", 0)

        if (header.size >= 88 && littleEndianDword(header, 0) == 1 && littleEndianDword(header, 4) >= 88) {
            return Detection("EMF", 0)
        }

        val window = minOf(WINDOW, header.size)
        indexOf(header, window, PNG)?.let { return Detection("PNG", it.toLong()) }
        indexOf(header, window, JPEG)?.let { return Detection("JPEG", it.toLong()) }
        indexOf(header, window, GIF)?.let { return Detection("GIF", it.toLong()) }
        indexOf(header, window, PDF)?.let { return Detection("PDF", it.toLong()) }
        if (header.size >= 8) {
            indexOf(header, window, WMF_LE)?.let { return Detection("WMF", it.toLong()) }
        }
        return Detection("UNKNOWN", 0)
    }

    private fun startsWith(header: ByteArray, sig: ByteArray): Boolean =
        header.size >= sig.size && sig.indices.all { header[it] == sig[it] }

    /** [buf][offset] 始まりの4バイトをリトルエンディアン DWORD として読む。 */
    private fun littleEndianDword(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)

    /** [buf] の先頭 [window] バイト内で [sig] の出現位置。なければ null。 */
    private fun indexOf(buf: ByteArray, window: Int, sig: ByteArray): Int? {
        if (window < sig.size) return null
        outer@ for (pos in 0 until window - sig.size + 1) {
            for (i in sig.indices) {
                if (buf[pos + i] != sig[i]) continue@outer
            }
            return pos
        }
        return null
    }
}
