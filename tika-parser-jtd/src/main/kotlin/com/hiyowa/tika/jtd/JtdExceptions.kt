package com.hiyowa.tika.jtd

/**
 * JTD 解析・展開で使用する例外階層。OpenJTD rjtd-core/src/error.rs の移植。
 *
 * Rust 側は 5 種の `Error` enum（error.rs 6-16行）だが、Kotlin では例外クラス階層に
 * 分解する。表示文字列は Rust の `impl fmt::Display for Error`（error.rs 18-36行）と同一にする。
 *
 * 接頭辞の付与は基底 [JtdException] が担当し、サブクラスは例外種ごとの接頭辞と本文のみ
 * を渡す（呼び出し側は本文しか知らない設計）。
 */

/**
 * 基底例外。Rust の [crate::Error] に相当する。
 *
 * 表示文字列は `"$prefix$detail"` で構成される。[prefix] は各サブクラスが例外種に
 * 対応する接頭辞（末尾の空白込み）を固定し、[detail] は呼び出し側から渡される本文。
 */
open class JtdException(
    prefix: String,
    detail: String,
) : Exception("$prefix$detail")

/** 不正データ（解析不能な入力）。Rust `Error::InvalidData`（error.rs 21行）。Display 形式: "invalid data: {message}"。 */
class InvalidDataException(message: String) : JtdException("invalid data: ", message)

/** 対象が見つからない。Rust `Error::NotFound`（error.rs 22行）。Display 形式: "not found: {message}"。 */
class NotFoundException(message: String) : JtdException("not found: ", message)

/** 未対応機能（例: -lh5- 以外の LHA メソッド）。Rust `Error::Unsupported`（error.rs 23行）。
 * Display 形式: "unsupported feature: {feature}"。
 */
class UnsupportedFormatException(feature: String) : JtdException("unsupported feature: ", feature)

/**
 * リソース上限超過。Rust `Error::ResourceLimit { resource, limit, actual }`（error.rs 11-15行）。
 *
 * @property resource 超過したリソース名（Rust 側と文字単位で同一）。
 * @property limit 上限値。
 * @property actual 実際の値。
 */
class ResourceLimitException(
    val resource: String,
    val limit: Long,
    val actual: Long,
) : JtdException("resource limit exceeded: ", "$resource (limit $limit, actual $actual)")
