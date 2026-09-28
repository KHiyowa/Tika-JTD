# JTD 仕様リファレンス (Specifications & RFCs)

本ディレクトリは、前身プロジェクト **OpenJTD (`openjtd-spec`)** においてリバースエンジニアリングおよび実データ調査によって確立された仕様書・RFC (Request for Comments) を、Tika JTD+ 向けに引き継ぎ・管理する場所です。

プロジェクトの背景や設計方針の詳細は、ルートの [README.md](../../README.md) を参照してください。

---

## 採用方針と選定基準

Tika JTD+ では、**「エディタ・描画エンジンの夢を放棄し、テキストと構造の抽出に全振りする」** という設計方針を採用しています。

そのため、`openjtd-spec/rfc` の全 10 件のうち、バイナリ仕様・コンテナ構造・テキスト/シート抽出に直結する **9 件 (RFC 0001, 0002, 0003, 0005, 0006, 0007, 0008, 0009, 0010)** を採用し、レンダラ・中間ドキュメントモデル・PDF エクスポート構想を主眼としていた **RFC 0004 は除外** しました。

また、Tika JTD+ で新たに解読・実装された仕様（オブジェクト枠からの埋め込みドキュメント抽出等）については、後続の RFC（**RFC 0011〜**）として本仕様空間で継続的に標準化・管理します。

---

## RFC 目録

| RFC | 仕様名 (English / 日本語) | 著者 (Author) | 分類 | Tika JTD+ での実装・対応状況 |
|---|---|---|---|---|
| **0001** | [JTD Container Inventory](rfc/0001-container.md)<br>[JTD コンテナインベントリ](rfc/0001-container.ja.md) | KimEJ | コンテナ / CFB | Apache POI `POIFSFileSystem` によるコンテナ走査・ストリーム目録 (`JtdContainerReader`, `JtdFormatDetector`) |
| **0002** | [Ichitaro OpenOffice Filter Reference](rfc/0002-ichitaro-openoffice-filter.md)<br>[一太郎 OpenOffice フィルター参照](rfc/0002-ichitaro-openoffice-filter.ja.md) | KimEJ・MasKusuno | リファレンス / 互換性 | 歴史的 OpenOffice フィルタのクリーンルーム分析資料。ストリーム名 (`LayoutBoxText` 等) や SAX 要素対応の裏付け |
| **0003** | [DocumentText Initial Text Extraction](rfc/0003-document-text.md)<br>[DocumentText 初期テキスト抽出](rfc/0003-document-text.ja.md) | KimEJ・MasKusuno | 本文抽出 | `/DocumentText` の `SsmgV.01` マジック、UTF-16BE テキストラン、インラインマーカー (`DocumentTextParser`) |
| **0005** | [JTTC JustCompressedDocument Container](rfc/0005-jttc-just-compressed-document.md)<br>[JTTC JustCompressedDocument コンテナ](rfc/0005-jttc-just-compressed-document.ja.md) | KimEJ | 圧縮コンテナ | `/JSCompDocument`、`JustCompressedDocument` マーカー、LHA `-lh5-` 伸張 (`CompressedDocumentReader`, `LhaDecoder`) |
| **0006** | [DocumentTextPositionTables Initial Mark Offsets](rfc/0006-document-text-position-tables.md)<br>[DocumentTextPositionTables 初期 Mark offset](rfc/0006-document-text-position-tables.ja.md) | KimEJ | 構造テーブル | `/DocumentTextPositionTables` (`TCntV.01`, `MarkV.01`) のオフセット仕様。高度な位置追跡・構造解析の参照資料 |
| **0007** | [Layout Mark Streams Initial Inventory](rfc/0007-layout-mark-streams.md)<br>[Layout Mark Streams 初期インベントリ](rfc/0007-layout-mark-streams.ja.md) | KimEJ・MasKusuno | レイアウトマーク | `/LineMark`, `/PageMark`, `/PaperMark` のヘッダおよび構造調査 |
| **0008** | [Object and Embedded Image Stream Candidates](rfc/0008-object-stream-candidates.md)<br>[Object and Embedded Image Stream Candidates](rfc/0008-object-stream-candidates.ja.md) | KimEJ | 埋め込み / 図形 | `EmbedItems`, `Embedding`, `LayoutBox`, `Figure` などのストリーム調査。レイアウト枠・埋め込みオブジェクトのテキスト救済の基盤資料 |
| **0009** | [Document Text Paragraph Records](rfc/0009-document-text-paragraph-record.md)<br>[DocumentText 段落レコード構造](rfc/0009-document-text-paragraph-record.ja.md) | KimEJ・MasKusuno・KHiyowa | 本文レコード構造 | `0x001c` レコード (class 0x0000/0x0010/0x0020/0x0030) の自己記述型可変長ヘッダ構造。`w4=0x008f` 行ヘッダのスパン数算術（$w_5=4(n-1)+3$）および本文側スタイル符号体系（RFC 0013 連係） |
| **0010** | [Document Sheets](rfc/0010-document-sheets.md)<br>[Document Sheets (マルチシート構造)](rfc/0010-document-sheets.ja.md) | KHiyowa | マルチシート | `/DocItemInfo` (UTF-16LE) および `/ObjectSheets/DocSheet/DOCS_XXXX/` 構造の解析 (`ObjectSheetsReader`) |
| **0011** | [ObjectBox and Embedded Document Extraction](rfc/0011-object-box-embedded-documents.md)<br>[オブジェクト枠 (ObjectBox) と埋め込みドキュメント抽出仕様](rfc/0011-object-box-embedded-documents.ja.md) | KHiyowa | 埋め込み / 再帰抽出 | `Embedding N` / `OleItem N` ストレージ走査、生 BIFF8 の CFB ラップ (`Workbook`)、WMF スライス (`\x03EmbeddedPress`)、Tika 再帰抽出 (`ObjectBoxExtractor`) |
| **0012** | [JSEQ Equation Object and MATH.VAF Container Specification](rfc/0012-jseq-equation-object.md)<br>[JSEQ 数式オブジェクト枠と MATH.VAF コンテナ仕様](rfc/0012-jseq-equation-object.ja.md) | KHiyowa | 数式 / オブジェクト枠 | (Draft) `JSEQ3Contents` (`MATH.VAF`) コンテナ階層、ヘッダレイアウト、1,162B 固定長不変量、全文字パレット自動導出プロトコル |
| **0013** | [Rule Flow Reconstruction Model and Table SAX Projection Specification](rfc/0013-structured-sax-flow-contract.md)<br>[罫線流路復元モデルと表構造 SAX 射影仕様（HTML Table / Markdown Contract）](rfc/0013-structured-sax-flow-contract.ja.md) | KHiyowa | 構造化出力 / 罫線表 | (Draft) 罫線流路復元 Doctrine、確定的行境界（`0x000e`）、スパン Coalesce 規則、列数算術、スタイル符号（行内/行間・BOX文法）、安定グリッド判定による表状態機械、XHTML / GFM Markdown 二重契約 |

---

## 除外した RFC について

- **RFC 0004: Initial Document Model Export (`0004-document-model-export`)** (著者: KimEJ)
  - **除外理由**: 旧 `rjtd` における Rust 独自の中間モデル（`Document` / `DocumentSheet` / `Metadata`）の階層構造や、CanvasKit Replay・PDF/Markdown/HTML エクスポート構想を論じた設計文書です。
  - Tika JTD+ では中間モデルを構築してレンダリングするアプローチを採らず、Apache Tika の標準仕様に則って SAX イベントストリーム (`ContentHandler`) へ直接テキストおよび構造を出力する設計を採用しています。
