package com.hiyowa.tika.jtd

/**
 * RFC 0009 準拠の DocumentText 定数群。
 *
 * 移植元: OpenJTD `rjtd-core/src/document_text.rs` のパブリック定数。
 * マジック/セグメント名などのプライベート定数はパーサー本体に置き、
 * ここには RFC 0009 で意味を持つマーカー・制御コード・ストリームパスを集約する。
 */
object DocumentTextConstants {

    // ストリームパス
    const val DOCUMENT_TEXT_PATH = "/DocumentText"
    const val COMPRESSED_DOCUMENT_PATH = "/JSCompDocument"
    const val EMBEDDED_DOCUMENT_TEXT_PATH = "/EmbeddedDocumentText"

    // 制御境界コード。これらは reading_text を維持したまま ControlBoundary として emit される。
    // 0x000c はページ切れ（フォームフィード、RFC 0003）
    const val DOCUMENT_TEXT_PAGE_BREAK_CONTROL = 0x000c
    // 0x0010 はテキスト run 内のインラインスペース / 整形境界制御
    const val DOCUMENT_TEXT_INLINE_SPACE_CONTROL = 0x0010
    // 0x000e は 0x001c/0x0030 のテーブルセルレコードを区切り、reading_text は跨いだまま維持される
    const val TEXT_ROW_DELIMITER = 0x000e

    // RFC 0009: 0x001c レコード開始マーカー（レコードを開くためクラスコード群とは分離）
    const val RECORD_START_MARKER = 0x001c
    // RFC 0009: 0x001c レコードクラスコード（decoded: false — 構造は判明、意味は部分的）
    const val RECORD_CLASS_INLINE_CONTEXT = 0x0000
    const val RECORD_CLASS_PARAGRAPH_LINE = 0x0010
    const val RECORD_CLASS_TABLE_SECTION_TRANSITION = 0x0020
    const val RECORD_CLASS_TABLE_CELL = 0x0030

    // テキスト run / インラインテキストのマーカー
    const val TEXT_RUN_MARKER = 0x001f
    const val INLINE_TEXT_START = 0x001d
    const val INLINE_TEXT_END = 0x001e
}
