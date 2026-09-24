val tikaVersion = "4.0.0"
val poiVersion = "5.5.1"

dependencies {
    // Tika は実行時ホスト（tika-core）と同一 classpath に載せる前提だが、
    // README の組み込み例（implementation tika-core）との互換のため implementation とする。
    api("org.apache.tika:tika-core:$tikaVersion")
    // OLE2/CFB コンテナ解体は POI に委譲（core artifact だけでよい: POIFSFileSystem）
    implementation("org.apache.poi:poi:$poiVersion")

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
