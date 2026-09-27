# 変更履歴

このプロジェクトのすべての重要な変更はこのファイルに記録されます。

フォーマットは [Keep a Changelog](https://keepachangelog.com/ja/1.1.0/) に基づいており、
このプロジェクトは [Semantic Versioning](https://semver.org/spec/v2.0.0.html) に準拠しています。

## [Unreleased]

### 追加
- **オブジェクト枠（ObjectBox）からの埋め込みドキュメント再帰抽出機能**:
  - 文書内のオブジェクト枠ストレージ（`Embedding` / `OleItem`）を走査し、埋め込まれた表計算データ（BIFF8 / Excel）や図形（WMF 等）を抽出する機能を追加。
  - Apache Tika 4 の `EmbeddedDocumentExtractor` / `EmbeddedDocumentUtil` 契約に準拠し、埋め込みオブジェクトを透過的に再帰パース。
- **実世界コーパス耐性検証ハーネスおよび並列回帰テストレール**:
  - 大量コーパスでの再現性・安定性を担保するテスト機構の整備。
- **開発・解析用ツールモジュール（`tika-parser-jtd-tools`）**:
  - OLE2 CFB ストリーム構造やブロックアロケーションを可視化・ダンプする開発ツールの追加。

### 変更
- **CLIツールの刷新（`tika-parser-jtd-cli`）**:
  - Tika 公式 CLI（`tika-app`）互換のインターフェースへ全面的に刷新。
  - `--text`, `--metadata`, `--xml`, `--html`, `--jsonRecursive` などの Tika 標準オプションをそのまま利用可能に。
  - テキスト抽出処理のスレッドセーフ化による並列実行性能の向上。

### 削除
- **旧 CLI コマンド体系の廃止（破壊的変更）**:
  - Rust 実装時代および v0.1.0 まで提供されていた独自サブコマンド体系（`cat`, `export`, `sheets` 等）を廃止。
  - 従来の CLI 引数・構文との後方互換性は破棄され、Tika 公式 CLI 互換のオプション指定方式に一本化されました。

## [0.1.0] - 2026-09-26

### 追加
- Apache Tika 4.0.0 向け一太郎文書（`.jtd` / `.jtt` / `.jttc`）パーサー `JtdParser` の初期リリース。
- Apache POI（POIFS）を活用した純 JVM（Kotlin）実装による、外部ネイティブバイナリ（Rust 等）非依存の実現。
  - OLE2 / CFB（Compound File Binary）コンテナの解体は Apache POI に委譲し、一太郎独自ストリーム（`/DocumentText`, `/LayoutBoxText` 等）の復元ロジックを Kotlin で実装。
- 本文テキスト（`/LayoutBoxText`）の逐次復元（可変ピッチ・長 span 対応）。
- 複数シート（マルチシート）結合抽出および脚注（Footnote）のペアリング。
- 開封不能な破損 CFB に対する第2防衛線（サルベージ）テキスト抽出機能。
- 段落自動採番の復元および制御文字のトリム処理。
- 公式 Tika Server コンテナ（`/tika-extras`）への JAR ドロップインによる自動認識機構（MIME 型定義 `custom-mimetypes.xml` および ServiceLoader 設定の内包）。
- 初期 CLI ツール（`cat`, `export`, `sheets` サブコマンド）。

[Unreleased]: https://github.com/KHiyowa/tika-jtd/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/KHiyowa/tika-jtd/releases/tag/v0.1.0
