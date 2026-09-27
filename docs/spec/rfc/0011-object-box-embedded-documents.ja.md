# RFC 0011: オブジェクト枠 (ObjectBox) と埋め込みドキュメント抽出仕様

Status: accepted

Observed / Established: 2026-09-27

English version: [0011-object-box-embedded-documents.md](0011-object-box-embedded-documents.md)

## 概要

一太郎（Justsystem 一太郎）の文書（JTD / JTT）において、本文（`/DocumentText`）、ヘッダ（`/Header`）、レイアウト枠（`/LayoutBoxText`）とは別に、文書内に配置・埋め込まれた表計算、図形、OLE オブジェクト等の外部コンポーネントは**「オブジェクト枠（ObjectBox）」**として保持される。

前身の RFC 0008（*Object and Embedded Image Stream Candidates*）では未解読ストリーム候補（`Status: Diagnostic only`）として記録されていたが、本仕様はこれらオブジェクト枠の内部バイナリ構造および抽出・整流化プロトコルを確定・標準化するものである。

---

## 1. CFB ストレージ構造と走査規則

オブジェクト枠の実体は、CFB（Compound File Binary）コンテナ内の専用サブストレージ配下に格納される。

### 1.1 代表的なストレージ配置例

```text
/Embedding 1/                                (Storage: オブジェクト枠 1)
/Embedding 1/\x03EmbeddedPress               (Stream: 図形 press / WMF)
/Embedding 1/\x03EmbeddedPress2              (Stream: 図形 press 予備)
/Embedding 1/Workbook                        (Stream: 生 BIFF8 表計算)
/Embedding 2/                                (Storage: オブジェクト枠 2)
/Embedding 2/Workbook
...
/OleItem 1/                                  (Storage: OLE オブジェクト枠)
/OleItem 1/\x03EmbeddedPress
/OleItem 1/Workbook
```

### 1.2 走査規則 (Storage Discovery)

1. **対象ストレージの識別**:
   - ディレクトリエントリ名が `Embedding` または `OleItem` で始まるサブストレージ（大文字・小文字を区別しない）をオブジェクト枠ストレージとして識別する。
2. **走査順序の決定論性**:
   - ディレクトリエントリの走査は、エントリ名の小文字正規化昇順（lexicographical order）でソートして行い、抽出順序の完全な決定論性を保証する。
3. **探索境界とリソース制限**:
   - **走査深さ上限**: `MAX_EMBEDDED_DEPTH = 4`（深さ 5 以上の探索は打ち切る）。
     > **注記**: 後続の画像・EMF 拡張実装（`feat/embedded-images-and-emf`）における実データ検証により、再帰深さ上限を `8` へ引き上げることが予定されている。
   - **抽出オブジェクト数上限**: 単一文書あたりの最大委譲オブジェクト数を `MAX_EMBEDDED_OBJECTS = 64` に制限する。

---

## 2. 表計算オブジェクト枠 (`Workbook`) の整流化仕様

### 2.1 バイナリ構造

オブジェクト枠内の `Workbook` ストリームは、以下のいずれかの形態をとる：

1. **生 BIFF8/OLE1.0 ストリーム**:
   - 先頭 4 バイトが `0x09 0x08 0x10 0x00`（BIFF8 BOF レコード: Record Type `0x0809`, Length `0x0010`）。
   - 標準的な OLE2/CFB ヘッダ（`0xD0 0xCF 0x11 0xE0`）を持たない、生の Excel ワークブックバイナリ。
2. **標準 OLE2 CFB コンテナ**:
   - 既に独立した CFB コンテナとしてシリアライズされている形態。

### 2.2 整流化 (Normalization / Wrapping) 規約

標準的なパーサー（Apache POI HSSF 等）は CFB コンテナにラップされた `Workbook` ストリームを前提とするため、生 BIFF8 ストリームに対して以下の整流化を行う：

- 最小限の空 OLE2 CFB コンテナ（`POIFSFileSystem`）をインメモリで動的に構築し、そのルートに名前 `"Workbook"` のストリームとして生ペイロードを格納する。
- 既に CFB コンテナである場合は、加工せずそのまま透過委譲する。

### 2.3 メタデータ契約

- **Content-Type**: `application/vnd.ms-excel`
- **Resource Name**: `embedded-${index}.xls`（`index` は 0 始まりの通し番号）
- **Relationship ID**: 当該ストリームの CFB パス（例: `/Embedding 1/Workbook`）

---

## 3. 図形 press オブジェクト枠 (`EmbeddedPress`) の切り出し仕様

### 3.1 バイナリ構造

ストリーム名 `\x03EmbeddedPress` または `\x03EmbeddedPress2` は、図形やメタファイル描画データを格納する。

- **先頭マジック**: 先頭 8 バイトが ASCII 文字列 `"METAFILE"`（`0x4d 0x45 0x54 0x41 0x46 0x49 0x4c 0x45`）で始まる。
- **ヘッダプレフィックス**: マジック直後にジャストシステム固有のヘッダ領域が続く。
- **WMF ヘッダマーカー**: オフセット `0x08` から最大 `0x60` 付近までの範囲（偶数境界探索）に、Windows Metafile の標準レコード開始マーカー（`0x01 0x00 0x09 0x00`）が出現する。

### 3.2 切り出し (Slicing) 規約

1. 先頭 8 バイトが `"METAFILE"` と一致しない場合は、有効な図形 press ではないと判定してスキップする。
2. オフセット 8 から探索範囲（偶数ステップ）内で WMF マーカー（`0x01 0x00 0x09 0x00`）を探索する。
3. マーカー発見位置を先頭として、ストリーム終端までのバイト列を WMF ペイロードとしてスライス（切り出し）する。

### 3.3 テキスト特性

切り出された WMF 内部には、Shift_JIS エンコーディングのテキストレコード（`ExtTextOut` / `TextOut`）が含まれており、図の軸ラベル、凡例、注記、あるいは文書本文の一部のテキスト情報が保持されている。

### 3.4 メタデータ契約

- **Content-Type**: `image/wmf`
- **Resource Name**: `embedded-${index}.wmf`
- **Relationship ID**: 当該ストリームの CFB パス（例: `/Embedding 1/\x03EmbeddedPress`）

---

## 4. 埋め込みドキュメント抽出プロトコル (Extraction Lifecycle)

### 4.1 パイプライン結合

親パーサー（`JtdParser`）は、ルート本文（`/DocumentText`、`/Header`、`/LayoutBoxText`）のレンダリング完了後、親の XHTML ボディスコープが閉じる直前にオブジェクト枠抽出処理（`extractEmbeddedDocuments`）を実行する。

```
[ JtdParser.parse ]
     │
     ├─ 1. ルート本文 XHTML レンダリング (DocumentText / Header / LayoutBoxText)
     │
     ├─ 2. ObjectBoxExtractor.extractEmbeddedDocuments
     │       ├─ CFB ディレクトリ走査 (Embedding / OleItem)
     │       ├─ バイナリ整流化 (生 BIFF8 の CFB ラップ / WMF スライス)
     │       └─ Tika EmbeddedDocumentExtractor への委譲
     │
     └─ 3. 親 XHTML ボディ終了
```

### 4.2 Tika `EmbeddedDocumentExtractor` との連携

1. `extractor.shouldParseEmbedded(metadata, context)` を評価し、抽出対象として承認された場合のみ通し番号 `index` をインクリメントする。
2. `extractor.parseEmbedded(stream, handler, metadata, context, false)` を呼び出し、親文書の `ContentHandler`（XHTML）にインライン結合する。

### 4.3 フォールトトレランス（例外隔離）

個別の埋め込みオブジェクトのパース時に発生した例外（`SAXException`、`IOException`、パーサー内部例外等）は個別オブジェクト単位で捕捉・消費し、後続のオブジェクト枠走査および親文書の出力を絶対に巻き込み破綻させない隔離設計とする。
