plugins {
    id("com.gradleup.shadow")
    id("com.vanniktech.maven.publish")
}

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

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()

    coordinates(
        groupId = project.group.toString(),
        artifactId = project.name,
        version = project.version.toString(),
    )

    pom {
        name.set("tika-parser-jtd")
        description.set("Apache Tika 4 parser for Ichitaro (.jtd) document format")
        url.set("https://github.com/KHiyowa/tika-jtd")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("khiyowa")
                name.set("KHiyowa")
                url.set("https://github.com/KHiyowa")
            }
        }
        scm {
            connection.set("scm:git:git://github.com/KHiyowa/tika-jtd.git")
            developerConnection.set("scm:git:ssh://github.com/KHiyowa/tika-jtd.git")
            url.set("https://github.com/KHiyowa/tika-jtd")
        }
    }
}

// Maven Central 公開およびプロジェクト間依存用の標準 Thin JAR（tika-parser-jtd-<version>.jar）
// 依存関係（kotlin-stdlib, poi 等）は pom.xml を通じて解決させ、JAR Hell を防止する
tasks.jar {
    archiveClassifier.set("")
}

// 公式 Tika Server / Docker（/tika-extras）用のドロップイン JAR（tika-parser-jtd-<version>-server.jar）
// Tika 本体に含まれない kotlin-stdlib のみを内包し、JAR 1本でのポン置き動作を可能にする
tasks.shadowJar {
    archiveClassifier.set("server")
    mergeServiceFiles()
    dependencies {
        include(dependency("org.jetbrains.kotlin:kotlin-stdlib.*"))
        include(dependency("org.jetbrains:annotations.*"))
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

