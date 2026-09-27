plugins {
    id("com.gradleup.shadow")
}

val tikaVersion = "4.0.0"
val poiVersion = "5.5.1"

dependencies {
    implementation(project(":tika-parser-jtd"))
    // フル機能 Tika CLI および全パーサー群
    implementation("org.apache.tika:tika-app:$tikaVersion")
    // JtdContainerReader の公開 API（withFileSystem）が POIFSFileSystem を露出するため、
    // サブシート本体の読み出しに直接アクセスする本 CLI は POI をコンパイル期に必要とする
    // （実行期は :tika-parser-jtd 経由で同一バージョンが classpath に載る）。
    implementation("org.apache.poi:poi:$poiVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val runtimeClasspathDir = layout.buildDirectory.dir("libs/runtime-classpath")

val copyRuntimeClasspath by tasks.registering(Copy::class) {
    from(configurations.runtimeClasspath)
    into(runtimeClasspathDir)
}

// java -jar tika-parser-jtd-cli.jar が動くよう、依存を classpath マニフェストに載せる
tasks.jar {
    dependsOn(copyRuntimeClasspath)
    manifest {
        attributes(
            "Main-Class" to "com.hiyowa.tika.jtd.cli.MainKt",
            "Class-Path" to configurations.runtimeClasspath.get()
                .files.sortedBy { it.name }
                .joinToString(" ") { "runtime-classpath/${it.name}" },
        )
    }
}

// 依存関係をすべて内包した単体実行可能 Fat JAR（java -jar 1本で全パーサー・CLIが動作可能）
tasks.shadowJar {
    archiveClassifier.set("standalone")
    mergeServiceFiles() // SPI定義を正しく連結マージ
    manifest {
        attributes("Main-Class" to "com.hiyowa.tika.jtd.cli.MainKt")
    }
}

// runtime-classpath を同梱した配布用 ZIP アーカイブ
val distZip by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Bundles the CLI JAR and its runtime-classpath directory into a zip archive."
    archiveBaseName.set("tika-parser-jtd-cli")
    from(tasks.jar)
    from(copyRuntimeClasspath) {
        into("runtime-classpath")
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar, distZip)
}

// ローカルコーパス（git 未追跡・opt-in）から golden を採取する開発タスク（MIGRATION.md §10.5）
val captureGolden by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Capture golden stdout/exit outputs for the local corpus (testdata/corpus, git-ignored)"
    mainClass.set("com.hiyowa.tika.jtd.capture.GoldenCapture")
    classpath = sourceSets["test"].runtimeClasspath
    args(
        "--corpus", rootProject.projectDir.resolve("testdata/corpus"),
        "--out", rootProject.projectDir.resolve("testdata/golden/new"),
        "--root", rootProject.projectDir,
    )
}
