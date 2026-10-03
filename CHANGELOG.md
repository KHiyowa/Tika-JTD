# 変更履歴

このプロジェクトのすべての重要な変更はこのファイルに記録されます。

フォーマットは [Keep a Changelog](https://keepachangelog.com/ja/1.1.0/) に基づいており、
このプロジェクトは [Semantic Versioning](https://semver.org/spec/v2.0.0.html) に準拠しています。

## [0.3.0] - 2026-10-03

### 追加
- **一太郎罫線表の構造化出力（RFC 0013 流路モデル）**:
  - 一太郎文書（`.jtd` / `.jtt` / `.jttc`）内の罫線表を解析し、XHTML（`<table>` / `<tr>` / `<td>`）および Tika 4 既定 Markdown（GFM パイプ表）として構造化出力する機能を追加。
  - セル結合（`colspan` / `rowspan`）の近似判定に対応（XHTML で属性を保持。Markdown では GFM の制約により平坦化）。
  - セル内改行（折返し）の半角スペース平坦化により、Markdown 表構造の分断を防止。
  - 時間割様式、申請書様式、カレンダー（月暦）様式など、多様な帳票グリッドの再構成に対応（縦書き帯のセル集約、ヘッダ行の取り込み、字下げ折返しの論理行統合）。
  - 複数シート構造を `<div class="sheet">` ＋シート名 `<h2>`、レイアウト枠（枠内テキスト）を `<div class="layout-box">` ＋段落 `<p>` で階層構造化。
- **リバースエンジニアリング仕様書（RFC）の策定**:
  - 数式オブジェクト（JSEQ）のバイナリ構造仕様書（RFC 0012）。
  - 罫線流路構造化および SAX 射影契約仕様書（RFC 0013）。
- **開発・検証用ハーネスの追加**:
  - 一太郎印刷 PDF から罫線ベクトルとテキストを抽出してセルグリッド正解を生成・突合する PDF グリッドオラクルツール（`tools/pdf_grid_oracle/`）。
  - 全コーパスの XHTML 出力を一括高速採取しブラウザ確認用 CSS を注入する並列採取ハーネス（`XhtmlCapture`）。
  - 表構造骨格フィンガープリントおよび保存往復不変性を検証するオプトインテストレール（`CorpusGroundTruthRailTest` / `CorpusRuledBoxGroundTruthRailTest` 等）。

### 修正
- **文書冒頭・表外テキストの欠落修正（RFC 0013 追補 §13.1）**:
  - 特定のセグメント構造を持つ文書において、ヘッダ直後から表までの先頭テキスト（表題行など）が読み飛ばされていた不具合を修正。
- **インライン規則領域（FE0X）による文字化け・テキスト分断の修正**:
  - `/DocumentText` 中に挟まるインライン規則記録領域（`0xFE00`〜`0xFE0F`）により、本文テキストが分断されて巨大なバイナリノイズとして出力されていた不具合を修正。
- **`<body>` タグの重複出力の修正**:
  - `XHTMLContentHandler` が自動管理する `<body>` タグとパーサー側の手動出力が競合し、重複した `<body>` が出力されていた問題を解消。
- **破損コンテナサルベージ時の不要グリフ混入防止**:
  - サルベージテキスト抽出時に混入していた不要な制御文字・Latin-1 グリフを排除し、本文テキストの抽出精度を改善。

### 破壊的変更
- **XHTML 出力の構造化**:
  - `-x` / `PUT /tika/xml` の出力が、従来の「本文全体を1個のテキストノードとする形式」から、`<p>` / `<table>` / `<tr>` / `<td>` / `<div>` 等の構造化 XHTML イベントへ変更されました。
  - 整形改行の挿入に伴い、プレーンテキスト抽出（`-t` / `PUT /tika/text`）における空白・改行の位置が v0.2.x から変更されています（文字全量保存則はコーパス全件で検証済み）。
- **MIME タイプの変更 (`application/vnd.justsystem.ichitaro` → `application/vnd.justsystems.ichitaro`)**:
  - 開発元（ジャストシステム）の公式表記に合わせて、カスタム MIME タイプ名を `application/vnd.justsystems.ichitaro`（末尾 `s`）に変更しました。
- **Tika 4 既定 Markdown 出力での GFM パイプ表出力**:
  - 罫線表を含む文書を `PUT /tika` 等で処理した際、従来の平文テキストではなく GFM パイプ表（`| 列1 | 列2 |`）が出力されるようになります。

## [0.2.2] - 2026-09-28

### 破壊的変更
- **公式 Tika Server 用ドロップイン JAR のファイル名変更 (`tika-parser-jtd-*-server.jar`)**:
  - Maven Central に公開される主成果物（`tika-parser-jtd-0.2.2.jar`）が標準の Thin JAR となるようファイル名を整理しました。
  - これに伴い、公式 Tika Server（`/tika-extras`）および Docker コンテナへの配置を目的とした、`kotlin-stdlib` 内包版のドロップイン JAR ファイル名を **`tika-parser-jtd-<version>-server.jar`** へ変更しました。
  - 外部スクリプトや Dockerfile 等で GitHub Releases から直接パーサー JAR を取得している場合は、対象ファイル名を `-server.jar` に更新する必要があります。

### 改善
- **リリースアセットの整理**:
  - GitHub Releases の配布アセットから不要な補助 JAR を排除し、「Tika Server 用ドロップイン JAR（`tika-parser-jtd-*-server.jar`）」および「スタンドアロン CLI（`tika-parser-jtd-cli-*`）」の 3 成果物に厳選して明確化しました。

## [0.2.1] - 2026-09-28

### 不具合修正
- **公式 Tika Server へのドロップインで動作しない問題の修正（Kotlin 標準ライブラリの内包）**:
  - `tika-parser-jtd` 配布 JAR に、公式 Tika コンテナ（`apache/tika`）側に同梱されていない `kotlin-stdlib` が入っておらず、JAR 1本で動かない問題が発生していました。`kotlin-stdlib` および `annotations` を最小限バンドルする Shadow JAR 構成へ変更しました。
  - これにより、Tika Server の `/tika-extras/` に別途 `kotlin-stdlib.jar` を配置する必要がなくなり、**`tika-parser-jtd.jar` 1本を配置・マウントするだけ** で即座に稼働できるようになりました。

## [0.2.0] - 2026-09-27

### 追加
- **オブジェクト枠（ObjectBox）からの埋め込みドキュメント・画像再帰抽出機能**:
  - 文書内のオブジェクト枠ストレージ（`Embedding` / `OleItem` 等）を走査し、埋め込まれた表計算データ（BIFF8 / Excel）、ベクター画像（WMF / EMF）、およびラスタ画像（JPEG / PNG / GIF / BMP）を抽出する機能を追加。
  - Apache Tika 4 の `EmbeddedDocumentExtractor` / `EmbeddedDocumentUtil` 契約に準拠し、埋め込みオブジェクトを透過的に再帰パース。
  - 制御文字プレフィックスを含むストリーム名の正規化や、深度拡張によるネストされたオブジェクトの確実な走査に対応。
- **実世界コーパス耐性検証ハーネスおよび並列回帰テストレール**:
  - 500ファイル超の公的機関・実世界コーパスに基づくパース検証・耐性試験ハーネス（`CorpusToleranceVerifier`）の整備。
  - 文字マルチセット方式による再現率（カバレッジ）評価およびカテゴリ別集計レポートの自動出力に対応。
- **リバースエンジニアリング仕様書（RFC）の統合**:
  - OpenJTD 由来のバイナリ観測資産を整理・体系化した RFC 仕様書群（RFC 0001〜0011）を `docs/spec/` に追加。
- **ヘッダーレス（ファイル名未指定）環境でのマジックバイト自動検出対応**:
  - OpenWebUI 等のクライアントが `Content-Disposition` や拡張子を伴わずにファイルをストリーミング送信した場合でも、CFB コンテナ内部の一太郎固有ストリームマーカー（`SsmgV.01`, `DocumentText` 等）による優先度 60 のマジックバイト判定により、`application/vnd.justsystem.ichitaro` を自動特定してテキスト抽出できるよう改善。
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

[Unreleased]: https://github.com/KHiyowa/tika-jtd/compare/v0.3.0...HEAD
[0.3.0]: https://github.com/KHiyowa/tika-jtd/compare/v0.2.2...v0.3.0
[0.2.2]: https://github.com/KHiyowa/tika-jtd/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/KHiyowa/tika-jtd/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/KHiyowa/tika-jtd/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/KHiyowa/tika-jtd/releases/tag/v0.1.0
