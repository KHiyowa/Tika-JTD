# 実世界コーパス耐性検証ガイド

本ディレクトリ（`testdata/`）は、一太郎（.jtd）文書のパース精度・文字再現率（カバレッジ）を、一太郎からエクスポートされた Word 文書（.doc）と突合して自動検証するためのテストデータ置き場です。

実文書ファイル群を配置する `testdata/corpus/` は、プライバシーおよび著作権保護の観点から Git 管理対象外（`.gitignore`）となっています。コミッター各自のローカル環境で文書を配置して検証を行います。

---

## 1. ディレクトリ構造と文書配置ルール

コーパスフォルダ配下では、サブディレクトリを再帰的に走査して `.jtd` と `.doc` のペアを自動認識します。

```text
testdata/
├── README.md                     # 本ガイド（Git管理）
├── categories.example.tsv        # カテゴリ定義設定ファイルの雛形（Git管理）
└── corpus/                       # 実文書置き場（.gitignore）
    ├── categories.tsv            # カテゴリ定義設定ファイル（ローカル・任意）
    └── <カテゴリディレクトリ>/
        ├── single-sheet/         # 単一シート文書群
        │   ├── 文書A.jtd
        │   ├── 文書A.doc
        │   └── ...
        └── multi-sheet/          # マルチシート文書群
            ├── 修論.jtd
            ├── 修論_タイトルシート.doc
            ├── 修論_アブストラクトシート.doc
            ├── 修論_本文シート.doc
            └── ...
```

### 単一シート文書（single-sheet）
- 同一ディレクトリ内に、ベース名（拡張子を除いたファイル名）が一致する `.jtd` と `.doc` を配置します。
- 対応する `.doc` が存在しない `.jtd` は自動的にスキップされます。

### マルチシート文書（multi-sheet）
- JTD 内に複数シート（表紙、本文、目次等）が存在する文書です。
- 各シートを DOC に変換したファイルは、`[JTDベース名]_[シート名].doc`（またはシート名を含むファイル名）として同一ディレクトリ内に配置します。
- テストハーネスが JTD 内部のシート定義順序を自動取得し、定義順に DOC テキストを結合して突合します。
- **部分突合（Partial Coverage）のサポート**:
  - 全シート分の DOC を手動変換するのが困難な場合でも、存在する一部のシートの DOC のみでカバレッジ（再現率）を計算できます。
  - 未突合のシートが存在する場合は、テスト結果レポートに注記・警告として出力されます。

---

## 2. カテゴリ定義（設定ファイルとゼロコンフィグ）

### ゼロコンフィグ（設定ファイルなし）
設定ファイルを用意しなくても、`testdata/corpus/` 直下のサブディレクトリを自動スキャンします。
配下に `.jtd` と `.doc` の双方が存在するディレクトリが、ディレクトリ名そのままのカテゴリ名として自動認識されます。
「手元のフォルダを置いてすぐに試したい」場合は、フォルダを置くだけで追加設定不要です。

### 設定ファイルによるカスタマイズ（`categories.tsv`）
レポート上の表示名をきれいに整えたい場合や、特定のフォルダのみを評価対象にしたい場合は、設定ファイルを配置します。

1. 雛形をコピーして `testdata/corpus/categories.tsv` を作成します:
   ```bash
   cp testdata/categories.example.tsv testdata/corpus/categories.tsv
   ```
2. TSV 形式（タブ区切り）でカテゴリ名と対象ディレクトリを記述します:
   ```tsv
   # 表示カテゴリ名	対象ディレクトリパス
   裁判所	testdata/corpus/cou-web-jtd
   教育委員会・学校	testdata/corpus/edu-web-jtd
   中央省庁・国	testdata/corpus/gov-web-jtd
   地方自治体	testdata/corpus/lg-web-jtd
   都道府県警察	testdata/corpus/pol-web-jtd
   オーナー実文書	testdata/corpus/owner-jtd
   ```
   - パスは、プロジェクトルート相対（例: `testdata/corpus/cou-web-jtd`）、`testdata/corpus` からの相対（例: `cou-web-jtd`）、または絶対パスに対応しています。
   - 先頭が `#` の行や空行は無視されます。

---

## 3. テストの実行方法

コーパス耐性テストレールはオプトイン実行となっており、通常テスト（`./gradlew test`）ではスキップされます。

```bash
# コーパス耐性テストレールの実行
./gradlew test -Pgatagate.corpus=true
```

独自の設定ファイルを指定して実行することも可能です:
```bash
./gradlew test -Pgatagate.corpus=true -Pgatagate.corpus.config=/path/to/custom-categories.tsv
```

---

## 4. 出力レポート

テストを実行すると、`build/reports/` 配下に以下の検証レポートが出力されます。

| ファイル | 内容 |
| :--- | :--- |
| `build/reports/corpus-tolerance.md` | カテゴリ別のファイル数、合格率（カバレッジ 95% 以上の割合）、平均一致度の Markdown サマリー表 |
| `build/reports/multi-sheet-alignment-report.md` | マルチシート文書の充足度（Full / Partial）、欠損・未突合シート一覧のレポート |
| `build/reports/corpus-tolerance-details.tsv` | 全対象ファイルの個別カバレッジ、DOC/JTD 文字数、合否判定、シートステータスを含む詳細 TSV |
