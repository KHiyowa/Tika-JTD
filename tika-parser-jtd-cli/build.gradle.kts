val poiVersion = "5.5.1"

dependencies {
    implementation(project(":tika-parser-jtd"))
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

// 依存関係をすべて内包した単体実行可能 JAR（java -jar 1本で動作可能）
val standaloneJar by tasks.registering(Jar::class) {
    group = "build"
    description = "Assembles a standalone, fat executable JAR containing all dependencies."
    archiveClassifier.set("standalone")
    manifest {
        attributes("Main-Class" to "com.hiyowa.tika.jtd.cli.MainKt")
    }
    from(sourceSets["main"].output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) }
    }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
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
    dependsOn(standaloneJar, distZip)
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
