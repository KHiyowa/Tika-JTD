package com.hiyowa.tika.jtd

/**
 * DocumentText 読み出しの結果。移植元: OpenJTD `rjtd-core/src/document_text.rs` の
 * `DocumentTextPayload`（Rust 版の新規作成関数は `DocumentTextPayload::new`）。
 *
 * @property sourceName 発生源パス。名前付きストリーム経路は [DocumentTextConstants.DOCUMENT_TEXT_PATH]、
 *   埋め込みスキャン経路は [DocumentTextConstants.EMBEDDED_DOCUMENT_TEXT_PATH]。
 * @property bytes 生バイト列。埋め込み経路では複数 span を `[0, 0]` 区切りで連結したもの。
 * @property text 解析済みプレーンテキスト（埋め込み経路では複数 span のテキストを `"\n"` で連結）。
 */
class DocumentTextPayload(
    val sourceName: String,
    val bytes: ByteArray,
    val text: String,
)
