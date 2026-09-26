![Tika JTD+](docs/tika-jtd.jpg)

# Tika JTD+

**Tika JTD+** is an Apache-2.0 parser for Apache Tika, salvaging text and metadata from JustSystems Ichitaro documents (`.jtd`, `.jtt`, `.jttc`) for search indexing and RAG pipelines.

**Tika JTD+** は、ジャストシステム社の一太郎文書（`.jtd` / `.jtt` / `.jttc`）から高精度にテキストおよびメタデータを救出し、現代の検索基盤・データパイプラインへ接続するためのオープンソース（Apache-2.0）パーサープロジェクトです。

Apache Tika の拡張パーサーとして設計されており、孤立したレガシーバイナリを検索インデックスや大規模言語モデル（LLM）のコンテキストへと直接橋渡しします。

# 日本の30年の知を、未来へ。

カムパネルラ、どこまでもどこまでも一緒に行こう。

---

## 1. プロジェクトの信念：検索システムで読めない文書は、存在しないのと同じだ

1990年代から今日に至るまで、日本の官公庁、地方自治体、教育現場、法曹界、そして学術機関では、膨大な思考、政策、知見が一太郎のフォーマットに託されてきました。そこには、30年間にわたり日本社会を支え続けてきた無数の人々の言葉が刻まれています。

しかし、現代のデジタル情報環境において、「検索システムで読めない文書は、存在しないのと同じだ」という冷徹な現実に直面しています。

ファイルサーバの奥深くに眠る数十万の `.jtd` ファイルは、OSの刷新、閉域網の制約、クラウドへの移行、そしてAIやRAG（検索拡張生成）による知識活用の波の中で、不可視のデータと化しつつあります。読めない文書は参照されず、参照されない記録はやがて破棄されます。

失われかけている過去30年分の知を、現代の情報生態系へと還流させること。それが本プロジェクトの使命です。

---

## 2. なぜ前身（OpenJTD）は頓挫し、本プロジェクトは何を変えたのか

本プロジェクトは、先人たちが挑んだオープンソースプロジェクト **[OpenJTD](https://github.com/KimEJ/OpenJTD)（`rjtd`）** のコードと観測資産を受け継ぎ、根本的な方針転換を行って再出発したフォークです。

### 夢の挫折：WYSIWYGエディタという泥沼

元プロジェクトである OpenJTD は、韓国の HWP 互換プロジェクト（`rhwp`）の成功をモデルに立ち上がりました。その志は「オープンソースによる JTD の完全な描画エンジンおよびエディタの実現」という壮大なものであり、RFCを作成しながら試みられました。

しかし、その前に立ちはだかったのは、公開仕様書の存在しない日本のプロプライエタリ・バイナリの深淵でした。
一太郎は、ミリ単位の組版や縦書き、精密な印刷プレビューを実現するため、コードの深部から Windows の GDI/GDI+ やグラフィック描画APIと不可分に結びついています。1985年の「JX-WORD太郎」から40年の歴史の中で蓄積された独自外字、ネストされたレイアウト枠やオブジェクト枠、複数シート、そして開発元社内においてすらバイナリのみが遺産として残るような歴史的モジュールであり、RFCで定義できるような統一仕様というものは存在しませんでした。
クロスプラットフォームでピクセル単位の描画と編集を再現しようとする試みは、10万行におよぶ巨大なモデル層（`rjtd-model`）の迷宮を生み、幾多のトークンを溶かしました。

“If API credits become available, maintainers plan to use them for focused, auditable assistance with... This plan does not assume selection for any support program or receipt of credits; maintainers retain final review and merge decisions.”

——そうREADMEに祈りの言葉を遺し、OpenJTD のコミットは途絶えました。

### 方針転換：エディタの放棄、テキスト抽出への全振り

**Tika JTD+ は、エディタおよび完全な描画エンジンの夢を明確に放棄します。**

私たちが引き継ぐべきは、未完のレンダラーではなく、泥臭いリバースエンジニアリングによって得られた「ストリームの解読とバイナリ耐性」の知見です。

* **画面再現を捨て、テキストと構造の抽出に特化:** 見た目のレイアウトを完全再現する必要はありません。本文の流れ、表（テーブル）、箇条書き、見出し構造さえ正確に引っこ抜くことができれば、レイアウトの復元や要約、構造化は現代の言語モデルやMarkdownが肩代わりしてくれます。
* **JVM / Kotlin への再構築:** Rust による巨大な独自ASTの構築を止め、Apache Tika エコシステムに適合する Kotlin/Java 実装へとコアを絞り込みます。OLE2コンテナの解体は枯れ切った Apache POI に任せ、パーサーは「落ちないこと」「未知のタグに遭遇してもテキストをサルベージすること」だけに集中します。

---

## 3. 一太郎を葬り去るためではなく、未来へ繋ぐために

このプロジェクトは、一太郎を「過去の遺物」として淘汰・排除するためのツールではありません。むしろその逆です。**日本語ワープロソフト「一太郎」が、これからも表現のための愛機として将来へ生き残り、使われ続けていくために不可欠な防壁**だと考えています。

日本語の機微に寄り添い、美しい組版と語彙を支え続けてきた一太郎は、いまなお替えの利かない優れた道具です。
しかし、「組織の検索システムに入らないから」「AIに食わせられないから」「他部署と共有できないから」というシステムの都合によって、現場は一太郎を手放すことを余儀なくされてきました。

もし、一太郎で書かれた文書が、何の手間もなくTikaを通じて全文検索され、ElasticsearchやSolrでインデックスされ、ローカルLLMが難なく解釈できる世界を作ることができればどうでしょうか。
「互換性の壁」さえ取り払われれば、人々はシステムの都合に脅かされることなく、安心して一太郎を選び続けることができます。

私たちがバイナリを開くのは、一太郎という文化を終わらせるためではありません。一太郎が紡ぎ出した言葉たち、そして一太郎というソフトウェアそのものを未来へ繋ぐためです。

---

## 4. 主な特徴

* **Apache Tika ネイティブ対応:** `tika-core` の `Parser` インターフェースを実装。既存の Tika パイプライン（Apache Solr、Elasticsearch、OpenSearch、独自クローラー）に JAR を追加するだけで `.jtd` が透過的に認識されます。**公式 Tika Server の Docker コンテナにも、イメージの再ビルドなし・JAR 1 本のマウントだけで組み込めます**（→ [§6](#6-クイックスタート)）。
* **実戦的な耐性フォールバック:**
  * マーカーが存在せずいきなり本文が始まる初期型・簡易保存ストリームの復元
  * 複雑な申請書に多用される「ネストされたレイアウト枠」からのテキスト救出
  * 破損したセクターや未知の独自タグに遭遇してもクラッシュせず、前後のプレーンテキストを回収するフェイルセーフ設計


* **内部圧縮・旧形式への対応:**
  * `.jttc` 等に用いられる独自圧縮 `JustCompressedDocument`（LHA `-lh5-` 相当）の純粋 JVM デコーダを内蔵
  * OLE2 形式（一太郎8以降）および旧バージョン断片ストリームの識別


* **軽量・JVM クリーニング:** 描画系ライブラリや重厚な GUI フレームワーク、外部プロセスを一切抱えません。依存は純粋な JVM 実装の `tika-core` と `Apache POI`（OLE2/CFB 容器の解析担当）のみで、Rust バイナリやネイティブコードは不要です。サーバサイドやバッチ処理で高速に動作します。

---

## 5. 動作環境と対応範囲 (Requirements & Scope)

* **Java (JVM):** Java 17 以降 (Java 17, 21 LTS 動作確認)
* **Apache Tika:** 4.0.0 以上

> [!IMPORTANT]
> **出力形式について（v0.1.0 現在）:**
> 本バージョン（0.1.0）では、検索インデックスや RAG パイプラインでの利用を主眼としており、**Tika のプレーンテキスト出力のみに対応**しています。XHTML によるタグ構造化や Markdown 等の出力形式には対応していません。

> [!NOTE]
> **Tika 3系へのバックポートについて:**
> 本ライブラリは Apache Tika 4.0.0+ を前提として設計されています。既存環境の制約等で Tika 3系へのバックポートを希望される場合は、お気軽に [Issues](https://github.com/KHiyowa/tika-jtd/issues) までご要望・ユースケースをお寄せください。

---

## 6. クイックスタート

### Tika パイプラインへの組み込み

`pom.xml` または `build.gradle.kts` に本ライブラリを追加します。

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.apache.tika:tika-core:4.0.0")
    implementation("com.hiyowa:tika-parser-jtd:0.1.0")
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

### CLI による単体抽出

GitHub Releases から `tika-parser-jtd-cli-0.2.0-standalone.jar`（すべての依存関係と全 Tika パーサーを内包した単体実行可能 Fat JAR）をダウンロードすれば、Apache Tika 公式 CLI（`tika-app`）と同一のインターフェースで単体実行できます。一太郎文書（`.jtd` / `.jtt` / `.jttc`）はもちろん、Tika がサポートする全形式を本 JAR 1 本で処理可能です。

```bash
# プレーンテキスト抽出（標準出力）
java -jar tika-parser-jtd-cli-0.2.0-standalone.jar --text path/to/document.jtd
# または短縮形
java -jar tika-parser-jtd-cli-0.2.0-standalone.jar -t path/to/document.jtd

# メタデータ一覧の出力
java -jar tika-parser-jtd-cli-0.2.0-standalone.jar --metadata path/to/document.jtd
# または短縮形: java -jar tika-parser-jtd-cli-0.2.0-standalone.jar -m path/to/document.jtd

# 構造化 JSON 出力（埋め込みメタデータ・テキスト）
java -jar tika-parser-jtd-cli-0.2.0-standalone.jar --jsonRecursive path/to/document.jtd
# または短縮形: java -jar tika-parser-jtd-cli-0.2.0-standalone.jar -J path/to/document.jtd

# XHTML 出力
java -jar tika-parser-jtd-cli-0.2.0-standalone.jar --xml path/to/document.jtd
# または短縮形: java -jar tika-parser-jtd-cli-0.2.0-standalone.jar -x path/to/document.jtd

# ヘルプと全オプションの表示
java -jar tika-parser-jtd-cli-0.2.0-standalone.jar --help
```

### 公式 Tika Server Docker へ JAR 1 本を配置して組み込む（推奨）

本パーサーの理想の姿は、**Apache Tika の公式コンテナを一切カスタムビルドせず、JAR 1 本を所定のディレクトリに配置するだけで `.jtd` が解析可能になる**ことです。これは、Tika 4 系の公式イメージを使用することで実現可能です。

```bash
docker run -d --name tika \
  -p 9998:9998 \
  -v "$PWD/jars/tika-parser-jtd-0.1.0.jar:/tika-extras/tika-parser-jtd.jar:ro" \
  apache/tika:latest-full
```

これにより、このサーバーは `.jtd` / `.jtt` / `.jttc` を処理できるようになります。

#### OpenWebUI などと一緒に docker compose で動かす

```yaml
services:
  tika:
    image: apache/tika:latest-full   # 公式イメージのまま。build: は不要
    ports:
      - "9998:9998"
    volumes:
      - ./jars/tika-parser-jtd.jar:/tika-extras/tika-parser-jtd.jar:ro
    restart: always
```

OpenWebUI 側は Admin Panel → Settings → Documents で Extract Engine に `Tika`、Server URL に `http://tika:9998` を指定するだけです。

#### 動作確認

```bash
# 標準のテキスト抽出エンドポイント
curl -T sample.jtd http://localhost:9998/tika

# Tika 4 の JSON テキストエンドポイント（OpenWebUI が使用）
curl -T sample.jtd http://localhost:9998/tika/json/text
```

#### なぜ「配置するだけ」で認識されるのか

公式 `apache/tika:latest-full`（4.0.0 実測）の ENTRYPOINT は、標準で `/tika-extras/` をクラスパスに含みます。

```
java -cp "/opt/tika-server/*:/opt/tika-server/lib/*:/tika-extras/*" \
  org.apache.tika.server.core.TikaServerCli -h 0.0.0.0
```

つまり、配置した JAR の中身が、そのまま Tika の読み込み機構に組み込まれます。

1. **`META-INF/services/org.apache.tika.parser.Parser`** — ServiceLoader が `com.hiyowa.tika.jtd.JtdParser` を自動登録し、`AutoDetectParser`（およびサーバーのパーサーチェーン）が `.jtd` を受け取るようになります。
2. **JAR ルートの `custom-mimetypes.xml`** — `application/vnd.justsystem.ichitaro`（glob: `*.jtd` / `*.jtt` / `*.jttc`）の MIME 定義が自動マージされます。外部の mimetypes 設定ファイルは不要です。
3. **純粋 JVM 実装** — OLE2 容器の解析は JVM 版 Apache POI が行うため、コンテナ内に Rust バイナリやネイティブライブラリを配置する必要がありません。

#### Docker を使わないローカル実行

Tika 4 には公式の「extras ディレクトリ」機構（`org.apache.tika.config.TikaExtras`）があります。JAR を置いたディレクトリをシステムプロパティで指定するだけで同様のことが可能です。

```bash
java -Dtika.extras.dir=./jars \
  -jar tika-server-standard-4.0.0.jar   # お使いの tika-server JAR
```

> [!NOTE]
> **前身の [`tika-openjtd`](https://github.com/KHiyowa/tika-openjtd) コンテナとの比較:**
> 旧構成では、Rust 版 `rjtd` を multi-stage ビルドでコンパイルし、`ExternalParser` + wrapper スクリプト + 外部 `tika-config.json` / `custom-mimetypes.xml` をコンテナ設定に組み込む必要がありました。Kotlin/JVM 化により、これらは **JAR 1 本と 1 行の volume マウント**に置き換わりました。

---

## 7. テストコーパスとコントリビューションについて

### 500ファイル超の実世界コーパスによる耐性検証

本パーサーの開発および耐性評価にあたっては、机上の仕様推測にとどまらず、日本の公共・実務現場で実際に流通している多様な実文書（計 **510 ファイル** の `.jtd`）をローカル検証コーパスとして網羅的なパース検証・耐性試験を実施しています。

一太郎で同じ文書を doc 出力したものと比較し、**テキストを 95% 以上再現できているものを合格**として判定しています。

| コーパス | ファイル数 | 合格率 | 平均一致度 |
| :--- | :--- | :--- | :--- |
| **裁判所** | 16 files | 93.8% | 0.991 |
| **教育委員会・学校** | 114 files | 74.6% | 0.955 |
| **中央省庁・国** | 236 files | 68.6% | 0.951 |
| **地方自治体** | 69 files | 59.4% | 0.910 |
| **都道府県警察** | 73 files | 26.0% | 0.878 |
| **オーナー実文書** | 2 files | 50.0% | 0.925 |
| **合計** | **510 files** | **63.3%** | **0.937** |

これにより、単一シートのシンプルな文書から、ネストされた複数レイアウト枠、多重罫線表、旧形式の OLE2 断片ストリームに至るまで、実戦的なフェイルセーフ動作を検証しています。

### 「銀河鉄道の夜」ポリシー（テストケース提供のお願い）

一太郎のバイナリ構造は極めて複雑であり、その堅牢性を担保するためには官公庁の申請書、裁判資料、教育指導案といった「複雑怪奇な現場のレイアウト」を踏み抜いたテストデータが不可欠です。
しかし、実業務の文書には機密情報や個人情報、再配布不可能な著作権が含まれます。

そのため、本プロジェクトでは「銀河鉄道の夜」置換ポリシーを採用しています。

> **バグ報告・テストケース提供のお願い:**
> パースに失敗するファイルに遭遇した場合、一太郎上でその構造（複雑な表組み、入れ子の枠線、ルビなど）を維持したまま、**中の文字列だけを宮沢賢治の『銀河鉄道の夜』（青空文庫 https://www.aozora.gr.jp/cards/000081/files/456_15050.html のパブリックドメインテキスト）に置き換えて保存したファイル**を作成し、テストケースとしてPRをお寄せください。

この方法により、機密や著作権の懸念を完全に排除した状態で、現実の凶悪なバイナリトポロジーのみをCIテストスイートに組み込むことが可能になります。

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
