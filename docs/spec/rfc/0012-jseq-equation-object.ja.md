# RFC 0012: JSEQ 数式オブジェクト枠と MATH.VAF コンテナ仕様

Status: draft

Observed / Established: 2026-09-28

English version: [0012-jseq-equation-object.md](0012-jseq-equation-object.md)

## 概要

一太郎（Justsystem 一太郎）の文書（JTD / JTT）において、JS 数式作成ツール（「はかどる！数式メーカー」等）によって作成された数式は、**「JSEQ 数式オブジェクト枠（`JSEQ.Document.3`）」** として OLE2 サブストレージ内に保持される。

本仕様は、数式オブジェクト枠の OLE2 コンテナ階層、ストリーム構成、および数式本体モデルを格納する `JSEQ3Contents`（マジック `"MATH.VAF"`）の内部バイナリ構造を定義・標準化するものである。

現時点において、コンテナ配置、ストリーム構成、ヘッダレイアウト、固定オーバーヘッド不変量、フォントテーブル仕様は確定済み（Accepted 相当）であるが、数式ツリーノードのオペコード体系および記号フォント固有の cmap（文字マッピング）についてはドラフト・調査中（Under Investigation）として定義する。

---

## 1. CFB コンテナ構造とオブジェクト枠識別

数式オブジェクト枠の実体は、シート別ストレージ配下の `EmbedItems` ディレクトリ内に `Embedding <id>` サブストレージとして格納される。

### 1.1 代表的なストレージ階層

```text
/ObjectSheets/DocSheet/DOCS_XXXX/             (Storage: シートストレージ)
  └ EmbedItems/                               (Storage: オブジェクト台帳ストレージ)
      ├ EmbeddingInfo                         (Stream: オブジェクト台帳 / CLSID 管理)
      ├ Embedding 1/                          (Storage: オブジェクト枠 1)
      │   ├ \x01CompObj                       (Stream: OLE2 コンポーネント情報)
      │   ├ JSEQ3Contents                     (Stream: 数式本体モデル / MATH.VAF)
      │   ├ \x03EmbeddedPress                 (Stream: 表示用スナップショット / JSSnapShot32)
      │   ├ \x03Contents                      (Stream: FORM スタブ / 34 バイト)
      │   ├ \x01Ole                           (Stream: OLE1 ブリック / 20 バイト)
      │   └ \x04JSRV_SegmentInformation       (Stream: セグメント情報 / 720 バイト)
      └ Embedding 2/                          (Storage: オブジェクト枠 2)
```

### 1.2 オブジェクト枠の識別仕様

1. **クラス識別子**:
   - `\x01CompObj` ストリーム内の型名: `一太郎数式オブジェクト`
   - ユーザータイプ文字列: `JSEQ3`
   - CLSID / Class 名: `JS.EqtnCtrl.1`
   - ProgID: `JSEQ.Document.3`
2. **走査規則**:
   - RFC 0011 §1.2 に準拠し、`EmbedItems` 配下の `Embedding <id>` ストレージを小文字正規化昇順で走査する。
   - `\x01CompObj` または `EmbeddingInfo` 台帳により `JSEQ.Document.3` クラスのストレージを数式オブジェクトとして識別する。

---

## 2. ストレージ内ストリーム構成

JSEQ3 数式ストレージ内の各ストリームの役割とサイズ仕様は以下の通りである。

| ストリーム名 | 代表サイズ | マジック / 形式 | 役割 |
| :--- | :--- | :--- | :--- |
| `\x01CompObj` | 99 B | OLE2 CompObj | OLE クラス名（`JS.EqtnCtrl.1` / `JSEQ3`）の宣言 |
| `JSEQ3Contents` | ~3.6〜7.8 KB | `MATH.VAF` (UTF-16LE) | **数式の本体モデル**（ツリーノード・文字コード・フォント定義） |
| `\x03EmbeddedPress` | ~8.5〜22 KB | `JSSnapShot32` (+ `GCI`) | 一太郎表示用グラフィックスナップショット（GCI ベクタ） |
| `\x03Contents` | 34 B | `FORM` スタブ | OLE コンテナ用の空スタブ（実体データなし） |
| `\x01Ole` | 20 B | OLE1 Header | OLE 1.0 互換ヘッダ |
| `\x04JSRV_SegmentInformation` | 720 B | バイナリ | 一太郎サーバー側の配置・セグメント管理情報 |

> **注意**: 一太郎から Word（.doc / RTF）へ書き出した場合、JSEQ オブジェクトは WMF（`TEXTOUT` レコード付きメタファイル）に変換されて出力されるが、**JTD 内部にはこの WMF は存在しない**（`\x03Contents` は 34 バイトの空スタブ）。したがって、JTD 単体から数式テキストを抽出するには `JSEQ3Contents` の直接解析が必須となる。

---

## 3. JSEQ3Contents (MATH.VAF) バイナリ仕様

`JSEQ3Contents` は、独自形式の構造化数式モデル（Vector Abstract Format）を UTF-16LE 準拠のレコード列として保持する。

### 3.1 ヘッダ構造

ストリーム先頭 32 バイト（`0x00`〜`0x1F`）のレイアウトは以下の通り。

```text
Offset (Hex)  Type    Field Name       Description
0x00 - 0x19   Bytes   Magic            "MATH.VAF\0\0..." (UTF-16LE: 4D 00 41 00 54 00 48 00 2E 00 56 00 41 00 46 00 ...)
0x1A - 0x1B   u16     Reserved         0x0000
0x1C - 0x1D   u16     VersionFlags     0x0103 (リトルエンディアン: 03 01)
0x1E - 0x1F   u16     BodyLength       本文レコード領域のバイト長 (L_body)
```

### 3.2 固定オーバーヘッド 1,162 バイト不変量

実測データ（29 式中 27 式で成立）より、ファイル全体のバイト長 $S_{total}$ と、オフセット `0x1E` に記録された本文バイト長 $L_{body}$ の間には以下の厳密な不変量が成立する。

$$S_{total} - L_{body} = 1,162 \text{ バイト}$$

この差分 1,162 バイトは、以下の領域で構成される：
1. **固定ヘッダ領域**: 先頭 32 バイト
2. **固定フッタ／フォントテーブル領域**: 末尾 1,130 バイト

> **変種（Variants）に関する注記**:
> 実測された 29 式中 2 式において、差分が 1,334 バイト（+172 B）および 990 バイト（-172 B）となる変種が観測されている。これらは特定の補助スタイルセクションの有無に起因するとみられる。

### 3.3 末尾フォント名テーブル

ストリーム終端部には、数式内で使用されるフォント名テーブルおよびスタイルスロットが記録される。

- **使用フォント例**:
  - `Times New Roman`（英字変数・数字）
  - `JustUnitMark`（数学記号・単位記号）
  - `JustOubunMark`（ギリシャ文字・欧文記号）
- **共通スロット ID**:
  - スタイルおよびレイアウト枠のスロット ID として `0x16E6` および `0x177A` が高頻度で出現する。

---

## 4. 調査中事項と解読プロトコル (Under Investigation)

数式テキストを自然な線形文字列（例: `f(x) = (a + b) / c`）として復元するために、以下の仕様策定・解析が進められている。

### 4.1 ツリー構造・ノードオペコード体系 (AST)

数式モデルは二次元の木構造（AST: Abstract Syntax Tree）としてシリアライズされている。

- **子ノードレコード構造**:
  - `[x][0x94][1][2][0][子ID][1][x][0]` のようなレコード列が観測されており、$x$ は単調増加するレイアウト X 座標を表す。
- **解読課題**:
  - 分数（Fraction）、根号（Radical / Sqrt）、上下添字（Superscript / Subscript）、括弧等のコンテナノードのオペコード同定。
  - DFS（深さ優先探索）走査時の巡回順序規則（分子 $\to$ 分母、基底 $\to$ 上付き $\to$ 下付き）の標準化。

### 4.2 記号フォント固有 cmap（文字マッピング）

`JustUnitMark` や `JustOubunMark` で参照される文字コードは標準 Unicode ではなく、フォント固有のグリフインデックス（例: `b`, `R`, `j`, `1` 等）となっている。

#### 自動 cmap 生成プロトコル (Full-Palette Export Protocol)

この文字マッピング辞書を網羅的かつ安全に構築するため、以下の手法を採用する：

1. **全記号パレット文書の作成**:
   - 一太郎の数式作成ツール GUI 上で入力可能なすべての数学記号・演算子・ギリシャ文字を網羅した合成 JTD 文書を作成する（原本資産を使用せず、銀河鉄道の夜置換ポリシーにも抵触しないクリーンなテストデータ）。
2. **Word (.doc / RTF) への書き出し**:
   - 一太郎の DOC 書き出し機能を実行する。一太郎内部のエクスポートエンジンが JSEQ オブジェクトを WMF の `TEXTOUT`（Unicode 文字列）へ自動変換する。
3. **1:1 辞書の自動導出**:
   - 合成 JTD 側の `JSEQ3Contents`（独自バイト列）と、DOC 側 WMF の `TEXTOUT` レコード（正解 Unicode 文字列）をスクリプトで自動突合し、グリフインデックス $\to$ Unicode の変換マップテーブル（cmap）を機械的に導出する。

### 4.3 表示キャッシュ未保持バリアントへの対応

観測された数式バイナリのうち、一部はマークレコード（グリフ表示キャッシュ）を持たず、ツリー構造のみを保持している。テキスト抽出器はマークレコードの有無に依存せず、AST ツリーのノード走査を主系統として線形化を行う設計とする。

---

## 5. Tika JTD+ における抽出パイプライン構想

```text
[Embedding N / JSEQ3Contents]
             │
             ▼
    [MATH.VAF Header 検証] (Magic, 0x1E 本文長)
             │
             ▼
    [AST Node Parser] (可変ボディ領域のツリー走査)
             │
      ┌──────┴──────┐
      │             │
[標準英数字]    [記号フォント] (JustUnitMark 等)
 (Unicode)          │
                    ▼
           [cmap Table 解決] (自動導出マップ)
                    │
      ┌─────────────┘
      ▼
[Linear Text Formatter] (DFS 巡回・分数/添字の平坦化)
      │
      ▼
(Tika XHTML SAX イベント: <p class="equation"> として出力)
```
