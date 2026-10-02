<p align="center">
  <img src="docs/tika-jtd.jpg" alt="Tika JTD+" width="70">
</p>

# Tika JTD+

## 一太郎文書を、検索できる知識へ。

**Tika JTD+** is an Apache-2.0 parser for Apache Tika, salvaging text and metadata from JustSystems Ichitaro documents (`.jtd`, `.jtt`, `.jttc`) for search indexing and RAG pipelines.

**Tika JTD+** は、ジャストシステム社の一太郎文書（`.jtd` / `.jtt` / `.jttc`）からテキストおよびメタデータを救出し、現代の検索基盤・データパイプライン・RAG（検索拡張生成）へ接続するための Apache Tika 向けオープンソース（Apache-2.0）拡張パーサーです。

* **Apache Tika 4 統合:** 既存の Tika パイプライン（OpenWebUI、Elasticsearch、Solr、OpenSearch、独自クローラー等）に JAR を追加するだけで透過的に接続。
* **純粋 JVM 実装:** Apache POI（`POIFSFileSystem`）による OLE2/CFB 解体と Kotlin による独自ストリーム復元。外部プロセスや Rust 等のネイティブバイナリに依存しません。
* **オブジェクト枠の再帰抽出:** 表計算（Excel / BIFF8）や図形（WMF）等の埋め込みオブジェクトを、Tika の `EmbeddedDocumentExtractor` 契約を通じて再帰的に解析。
* **実文書 510 ファイルによる検証:** 官公庁・自治体・学校などの実流通コーパスで平均一致度 93.7% の耐性を実証。
* **公式 Docker Server にポン置き対応:** 公式の `apache/tika` コンテナにイメージ再ビルド不要で、JAR 1 本をマウントするだけで即座に稼働。

> 知は、誰かの手元に眠っているだけでは、まだ公共の知とはいえない。  
> Tika JTD+ は、一太郎文書に刻まれた日本の30年の知を読み解き、誰もが参照できる形へと送り出す、現代の活版所です。
>
> カムパネルラ、どこまでもどこまでも一緒に行こう。

---

## 1. クイックスタート (Quick Start)

### 1.1 公式 Tika Server Docker へ JAR 1 本を配置して組み込む（推奨）

本パーサーの最大の特徴は、**Apache Tika の公式コンテナを一切カスタムビルドせず、JAR 1 本を所定のディレクトリに配置するだけで `.jtd` が解析可能になる**ことです。Tika 4 系の公式イメージを使用することで即座に実現できます。

```bash
docker run -d --name tika \
  -p 9998:9998 \
  -v "$PWD/jars/tika-parser-jtd-0.2.2-server.jar:/tika-extras/tika-parser-jtd.jar:ro" \
  apache/tika:latest-full
```

#### OpenWebUI などと一緒に docker-compose で動かす

```yaml
services:
  tika:
    image: apache/tika:latest-full   # 公式イメージのまま。build は不要
    ports:
      - "9998:9998"
    volumes:
      - ./jars/tika-parser-jtd.jar:/tika-extras/tika-parser-jtd.jar:ro
    restart: always
```

OpenWebUI 側は **Admin Panel → Settings → Documents** で Extract Engine に `Tika`、Server URL に `http://tika:9998` を指定するだけで、RAG ドキュメントとして `.jtd` を直接投入できるようになります。

#### 動作確認

```bash
# 標準のテキスト抽出エンドポイント
curl -T sample.jtd http://localhost:9998/tika

# Tika 4 の JSON テキストエンドポイント（OpenWebUI が使用）
curl -T sample.jtd http://localhost:9998/tika/json/text
```

#### なぜ「配置するだけ」で認識されるのか

公式 `apache/tika:latest-full`（4.0.0 実測）の ENTRYPOINT は、標準で `/tika-extras/` をクラスパスに含んでいます。

```bash
java -cp "/opt/tika-server/*:/opt/tika-server/lib/*:/tika-extras/*" \
  org.apache.tika.server.core.TikaServerCli -h 0.0.0.0
```

1. **`META-INF/services/org.apache.tika.parser.Parser`**: ServiceLoader が `com.hiyowa.tika.jtd.JtdParser` を自動登録し、`AutoDetectParser`（およびサーバーのパーサーチェーン）が `.jtd` を受け取るようになります。
2. **JAR ルートの `custom-mimetypes.xml`**: `application/vnd.justsystems.ichitaro`（glob: `*.jtd` / `*.jtt` / `*.jttc`）の MIME 定義が自動マージされます。外部の設定ファイル編集は不要です。
3. **純粋 JVM 実装**: OLE2 容器の解析は JVM 版 Apache POI が行うため、コンテナ内に Rust バイナリやネイティブ共有ライブラリを配置する必要がありません。

> [!NOTE]
> **前身の [`tika-openjtd`](https://github.com/KHiyowa/tika-openjtd) コンテナとの比較:**  
> 旧構成では、Rust 版 `rjtd` を multi-stage ビルドでコンパイルし、`ExternalParser` + wrapper スクリプト + 外部 `tika-config.json` / `custom-mimetypes.xml` をコンテナ設定に組み込む必要がありました。Kotlin/JVM 化により、これらは **JAR 1 本と 1 行の volume マウント**に置き換わりました。

#### Docker を使わないローカル実行

Tika 4 には公式の「extras ディレクトリ」機構（`org.apache.tika.config.TikaExtras`）があります。JAR を置いたディレクトリをシステムプロパティで指定するだけで同様のことが可能です。

```bash
java -Dtika.extras.dir=./jars \
  -jar tika-server-standard-4.0.0.jar
```

---

### 1.2 Tika パイプラインへの組み込み (Library)

`pom.xml` または `build.gradle.kts` に本ライブラリを追加します。

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.apache.tika:tika-core:4.0.0")
    implementation("com.hiyowa:tika-parser-jtd:0.2.2")
}
```

コードからの利用例：

```kotlin
import org.apache.tika.metadata.Metadata
import org.apache.tika.parser.AutoDetectParser
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import org.apache.tika.io.TikaInputStream
import java.nio.file.Path

fun main() {
    val parser = AutoDetectParser()
    val handler = BodyContentHandler(-1) // 容量無制限
    val metadata = Metadata()

    // Tika 4 系では parse() のストリーム型が TikaInputStream です。
    // Path から開くとファイル名 (.jtd) が MIME 検出にそのまま使われます。
    TikaInputStream.get(Path.of("sample.jtd")).use { stream ->
        parser.parse(stream, handler, metadata, ParseContext())
    }

    println("--- 抽出テキスト ---")
    println(handler.toString())
}
```

---

### 1.3 CLI による単体抽出 (Standalone CLI)

GitHub Releases から `tika-parser-jtd-cli-0.2.2-standalone.jar`（すべての依存関係と全 Tika パーサーを内包した単体実行可能 Fat JAR）をダウンロードすれば、Apache Tika 公式 CLI（`tika-app`）と同一のインターフェースで単体実行できます。

一太郎文書（`.jtd` / `.jtt` / `.jttc`）はもちろん、Tika がサポートする全形式を本 JAR 1 本で処理可能です。

```bash
# プレーンテキスト抽出（標準出力）
java -jar tika-parser-jtd-cli-0.2.2-standalone.jar --text path/to/document.jtd
# または短縮形: -t
java -jar tika-parser-jtd-cli-0.2.2-standalone.jar -t path/to/document.jtd

# メタデータ一覧の出力
java -jar tika-parser-jtd-cli-0.2.2-standalone.jar --metadata path/to/document.jtd
# または短縮形: -m
java -jar tika-parser-jtd-cli-0.2.2-standalone.jar -m path/to/document.jtd

# 構造化 JSON 出力（埋め込みメタデータ・テキスト）
java -jar tika-parser-jtd-cli-0.2.2-standalone.jar --jsonRecursive path/to/document.jtd
# または短縮形: -J
java -jar tika-parser-jtd-cli-0.2.2-standalone.jar -J path/to/document.jtd

# XHTML 出力
java -jar tika-parser-jtd-cli-0.2.2-standalone.jar --xml path/to/document.jtd
# または短縮形: -x
java -jar tika-parser-jtd-cli-0.2.2-standalone.jar -x path/to/document.jtd

# ヘルプと全オプションの表示
java -jar tika-parser-jtd-cli-0.2.2-standalone.jar --help
```

---

## 2. 動作環境と対応範囲 (Requirements & Scope)

* **Java (JVM):** Java 17 以降 (Java 17, 21 LTS 動作確認済)
* **Apache Tika:** 4.0.0 以上

> [!IMPORTANT]
> **ライブラリ出力形式について:**  
> `JtdParser` はプレーンテキスト抽出・メタデータ抽出に加え、**RFC 0013 に基づく罫線表の構造化 XHTML 出力に対応しました**（開発ブランチ `dev/v0.3.0`）。`-x` / `PUT /tika/xml` では `<p>` / `<table>` / `<tr>` / `<td>` / `<br/>` / `<div class="sheet">` / `<div class="layout-box">` の構造イベントが出力され、Tika 4 の既定 Markdown 出力（`PUT /tika`）では罫線表が GFM パイプ表として自動生成されます。
> **セル内改行の平坦化（Phase 3・§12.4 T3-1）:** SAX は 1 本のパイプであり、Tika の Markdown シリアライザ（commonmark）はセル内 `<br/>` をハードブレーク（末尾 2 空白＋改行）として出力します。GFM の表行は単一行でなければならないため、セル内に折返し（`0x000a`）があっても `<br/>` は出力せず**半角スペース 1 個へ平坦化**します（前後にテキストがない折返し＝スパン間前進マーカーは破棄、連続折返しは 1 個に集約）。段落レベルの `<br/>` は従来どおり出力します（ブロックレベルでは Markdown の構造を破壊しません）。
> **二重契約の注意点:** 結合セルの近似情報（`colspan` / `rowspan`）は XHTML 側のみ保持され、Markdown 側は GFM の制約により平坦化されます（実測: Tika 4 の Markdown シリアライザは列数の足りない行を自動補間しないため、結合セルの Markdown 側は末尾列を空欄とする GFM 妥当形式で素通しされます。補間が必要な単純な欠落槽はパーサー側で槽ごとの空セルを出します）。また、構造導入により XHTML シリアライザが整形改行を挿入するため、`-t` / `PUT /tika/text` の平坦化テキストは v0.2.x までとバイト一致しなくなります（契約は「空白の差分を許容するテキスト全量保存」へ移行。コーパス 596 ファイルで実質テキスト欠落ゼロを確認済み）。`-t` 高速パスは平文契約です。

> [!NOTE]
> **Tika 3系へのバックポートについて:**  
> 本ライブラリは Apache Tika 4.0.0+ を前提として設計されています。既存環境の制約等で Tika 3系へのバックポートを希望される場合は、お気軽に [Issues](https://github.com/KHiyowa/tika-jtd/issues) までご要望・ユースケースをお寄せください。

### 500ファイル超の実世界コーパスによる耐性検証

本パーサーの開発および耐性評価にあたっては、机上の仕様推測にとどまらず、日本の公共・実務現場で実際に流通している多様な実文書（計 **510 ファイル** の `.jtd`）をローカル検証コーパスとして網羅的なパース検証・耐性試験（`CorpusToleranceVerifier`）を実施しています。

一太郎から DOC 形式（Word 97-2003）へエクスポートした正解文書との間で文字マルチセット方式による再現率（カバレッジ）を算出し、**カバレッジ 95.0% 以上を達成したファイルを合格**として判定しています。

| コーパス種別 | ファイル数 | 合格率 | 平均一致度 |
| :--- | :--- | :--- | :--- |
| **裁判所** | 16 files | 93.8% | 0.990 |
| **教育委員会・学校** | 114 files | 78.1% | 0.962 |
| **中央省庁・国** | 236 files | 76.7% | 0.971 |
| **地方自治体** | 69 files | 62.3% | 0.916 |
| **都道府県警察** | 73 files | 23.3% | 0.874 |
| **オーナー実文書** | 2 files | 50.0% | 0.931 |
| **合計** | **510 files** | **67.8%** | **0.948** |

#### 検証ハーネスの算出アルゴリズムと評価指標の読み方

本検証ハーネス（`CorpusToleranceVerifier`）では、一太郎文書から出力された DOC 形式テキストを正解（Ground Truth）とし、以下のパイプラインで一致度を厳密に計測しています。

1. **テキスト正規化・クリーニング:**  
   双対のテキストに対し Unicode NFKC 正規化を実施し、空白類、制御文字、および罫線記号（`\u2500-\u259f`。DOC と JTD パーサー間の表組再現差異）を除去します。DOC 側の HTML 実体参照やページ番号等の抽出アーティファクトもクリーニングします。
2. **文字マルチセット（Bag of Characters）によるカバレッジ算出:**  
   正解 DOC に出現する文字の頻度に対し、JTD パーサーが抽出した文字の包含再現率（Coverage）を算出します。
   $$\text{Coverage} = \frac{\sum_{c} \min(\text{count}_{\text{doc}}(c), \text{count}_{\text{jtd}}(c))}{\sum_{c} \text{count}_{\text{doc}}(c)}$$
3. **合格判定:**  
   Coverage が **95.0% 以上** のファイルを「合格」と判定し、各カテゴリの合格ファイル比率を「合格率」、全ファイルの Coverage 算術平均を「平均一致度」として集計しています。

* **ルビ（ふりがな）未実装による合格率への影響:**  
  正解 DOC 側には一太郎が出力したルビ文字列が含まれますが、JTD パーサー側ではルビのインライン展開が現在仕様策定中（未実装）です。文字マルチセット方式では文字単位の出現頻度を厳密に測るため、人名・地名等にルビが多用される警察文書などでは、**本文テキストが極めて正確に抽出されていても、ルビ文字の欠落によってカバレッジが 90% 前後に留まり、95% の合格閾値を下回って「不合格」と判定される**ケースが多く生じます。
* **本文救出性能の実態:**  
  警察文書の合格率は 23.3% と低く見えますが、平均一致度（カバレッジ）は **0.874（全体平均は 0.948）** に達しています。これは、文書の主たる情報である本文テキストが高い精度で回収できていることを裏付けています。評価にあたっては合格率単独ではなく、平均一致度と併せて参照してください。

---

## 3. プロジェクトの信念：検索システムで読めない文書は、存在しないのと同じだ

1990年代から今日に至るまで、日本の官公庁、地方自治体、教育現場、法曹界、そして学術機関では、膨大な思考、政策、知見が一太郎のフォーマットに託されてきました。そこには、30年間にわたり日本社会を支え続けてきた無数の人々の言葉が刻まれています。

しかし、現代のデジタル情報環境において、「検索システムで読めない文書は、存在しないのと同じだ」という冷徹な現実に直面しています。

ファイルサーバの奥深くに眠る数十万の `.jtd` ファイルは、OSの刷新、閉域網の制約、クラウドへの移行、そしてAIやRAG（検索拡張生成）による知識活用の波の中で、不可視のデータと化しつつあります。読めない文書は参照されず、参照されない記録はやがて破棄されます。

失われかけている過去30年分の知を、現代の情報生態系へと還流させること。それが本プロジェクトの使命です。

---

## 4. OpenJTD から受け継いだ資産と、独自実装による完全互換の課題

本プロジェクトは、先人たちが挑んだオープンソースプロジェクト **[OpenJTD](https://github.com/KimEJ/OpenJTD)（`rjtd`）** のコードと観測資産を受け継ぎ、設計方針を根本から転換して再出発したフォークです。

### OpenJTD が目指すもの

OpenJTD は、韓国の HWP 互換実装（`rhwp`）の知見を手がかりに、JTD 形式の内部構造を解明し、テキスト抽出から文書モデル化、エクスポート、ビューア統合までを独自に実装することを目指している先駆的プロジェクトです。

公開仕様書の存在しないプロプライエタリな JTD バイナリに対し、泥臭いリバースエンジニアリングによってストリーム構造を解読し、観測事実を積み上げて形式のモデル化を試みたその功績は極めて大きく、後続の実装が頼るべき貴重な知見をもたらしています。

### 独自実装による完全互換の課題

一太郎は1985年の「JX-WORD太郎」から40年にわたり、ミリ単位の精密な組版や縦書き、日本語の表記慣習を支え続けてきた巨大なソフトウェアです。独自の文字コードや外字、入れ子のレイアウト枠、複数シート、さらにはExcel（BIFF8）や図形などの外部データを内包するオブジェクト枠など、長年の歴史の中で積み重なった仕様は極めて多岐にわたります。

OpenJTD の公開ドキュメント（0.0.1）でも「段落の完全な意味解釈、忠実なレイアウト、スタイル、表、ルビ、画像配置、ネイティブ編集は未完成」と言及されていた通り、バイナリの完全なモデル化や描画・編集エンジンをゼロから独自に再構築することは、極めて多くの技術的課題を伴う試みでした。

### 方針転換：一太郎固有の解析に集中し、汎用処理はエコシステムへ委ねる

**Tika JTD+ は、完全な描画・編集エンジンの独自構築を目指すのではなく、「一太郎固有のバイナリ解析とテキスト・構造の救出」に特化する方針を採りました。**

* **画面再現を捨て、テキストと構造の抽出に特化:** 見た目のレイアウトをピクセル単位で完全再現する必要はありません。本文の流れ、表（テーブル）、箇条書き、見出し構造さえ正確に引っこ抜くことができれば、レイアウトの復元や要約、構造化は現代の大規模言語モデル（LLM）やMarkdownが肩代わりしてくれます。
* **Apache Tika エコシステムとの協調（オブジェクト枠の再帰抽出）:** OLE2/CFB コンテナの解体は枯れ切った Apache POI に任せ、オブジェクト枠から取り出した埋め込みバイナリは Apache Tika の `EmbeddedDocumentExtractor` に委ねます。Excel なら POI、PDF なら PDFBox といった Tika が誇る膨大な既存パーサーチェーンに接続することで、自前で外部形式パーサーを抱え込むことなく、安全かつ網羅的な再帰解析を実現します。
* **JVM / Kotlin への再構築:** 外部プロセスやネイティブライブラリを排除し、純粋な JVM 実装として完結させました。これにより、公式 Tika Server への「JAR 1 本ドロップイン」が可能になり、本番環境への導入障壁を劇的に引き下げました。

---

## 5. 一太郎を葬り去るためではなく、未来へ繋ぐために

このプロジェクトは、一太郎を「過去の遺物」として淘汰・排除するためのツールではありません。むしろその逆です。**日本語ワープロソフト「一太郎」が、これからも表現のための愛機として将来へ生き残り、使われ続けていくために不可欠な防壁**だと考えています。

日本語の機微に寄り添い、美しい組版と語彙を支え続けてきた一太郎は、いまなお替えの利かない優れた道具です。
しかし、「組織の検索システムに入らないから」「AIに食わせられないから」「他部署と共有できないから」というシステムの都合によって、現場は一太郎を手放すことを余儀なくされてきました。

もし、一太郎で書かれた文書が、Tika を通じて全文検索され、Elasticsearch や Solr でインデックスされ、ローカル LLM が難なく解釈できる世界を作ることができればどうでしょうか。
「互換性の壁」さえ取り払われれば、人々はシステムの都合に脅かされることなく、安心して一太郎を選び続けることができます。

私たちがバイナリを開くのは、一太郎という文化を終わらせるためではありません。一太郎が紡ぎ出した言葉たち、そして一太郎というソフトウェアそのものを未来へ繋ぐためです。

---

## 6. テストコーパスとコントリビューション（「銀河鉄道の夜」ポリシー）

一太郎のバイナリ構造は極めて複雑であり、その堅牢性を担保するためには官公庁の申請書、裁判資料、教育指導案といった「複雑怪奇な現場のレイアウト」を踏み抜いたテストデータが不可欠です。
しかし、実業務の文書には機密情報や個人情報、再配布不可能な著作権が含まれます。

そのため、本プロジェクトでは**「銀河鉄道の夜」置換ポリシー**を採用しています。

> **バグ報告・テストケース提供のお願い:**  
> パースに失敗するファイルに遭遇した場合、一太郎上でその構造（複雑な表組み、入れ子の枠線、ルビなど）を維持したまま、**中の文字列だけを宮沢賢治の『銀河鉄道の夜』（[青空文庫](https://www.aozora.gr.jp/cards/000081/files/456_15050.html) のパブリックドメインテキスト）に置き換えて保存したファイル**を作成し、テストケースとしてPRをお寄せください。

この方法により、機密や個人情報の混入リスクを極小化しつつ、現実の凶悪なバイナリトポロジーのみを安全に CI テストスイートに組み込むことが可能になります。

---

## 謝辞（Acknowledgments）

Tika JTD+ がいまこうして前を向いて走ることができるのは、暗いバイナリの夜空に先立ってレールを敷き、荒野を切り拓いてくれた先人たちのコードと情熱があるからです。

銀河鉄道の旅の途中で、それぞれの場所へと静かに降りていかれた方々が、座席の上に遺してくれたかけがえのない道標に、心より敬意と感謝を捧げます。

* **OpenOffice.org 一太郎インポートフィルタ開発チームの皆様**  
  仕様書という地図すらなかった時代、リバースエンジニアリングという名の小さなランプを掲げ、JTD の内部構造を世界で初めてオープンソースの世界へ解き放ちました。OpenJTD、そして本プロジェクトが頼りにしている基礎解析ロジックの多くは、この偉大な先駆者たちが冷たい水辺で拾い集めてくれた最初の光に負っています。
* **kimeojin (KimEJ) 氏**  
  海を越えて JTD の現代的再実装という壮大な夢を描き、OpenJTD という美しい船を出航させた最初の船長。氏が `rjtd` に注ぎ込んだ解析コードと、あの膨大な観測記録がなければ、私たちは暗闇の中でどこへ向かえばよいのかすら分かりませんでした。
* **Masanori Kusunoki 氏**  
  デジタル社会の最前線から、切実な公共の実務を背負って難破船に飛び乗り、プロジェクトを前進させようと試みたコミッター。荒波の中で静かに祈りを捧げ、誠実に格闘を重ねたその足跡に深く敬意を表します。

車内に残された私たちは、暗い活版印刷所の片隅で小さな活字を拾い集めるように、30年分の言葉たちを一つひとつ救い出し、次の新しい世界へと連れていきます。

---

## ライセンス

本プロジェクトのソースコードおよびドキュメントは、**Apache License, Version 2.0** のもとで公開されています。

* ※「一太郎」「花子」および「JustSystems」は、株式会社ジャストシステムの商標または登録商標です。本書および本プロジェクトにおけるこれらの名称の使用は、互換性対象の客観的な記述・特定のみを目的としており、同社との提携・公認を意味するものではありません。
