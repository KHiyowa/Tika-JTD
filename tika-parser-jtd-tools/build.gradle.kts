plugins {
    id("com.gradleup.shadow")
}

val poiVersion = "5.5.1"

dependencies {
    // OLE2/CFB コンテナの走査・ダンプ専用。POI 本体だけで自己完結する（パーサー本体には非依存）
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

// java -jar tika-parser-jtd-tools.jar が動くよう依存を classpath マニフェストに載せる
tasks.jar {
    dependsOn(copyRuntimeClasspath)
    manifest {
        attributes(
            "Main-Class" to "com.hiyowa.tika.jtd.tools.OleTools",
            "Class-Path" to configurations.runtimeClasspath.get()
                .files.sortedBy { it.name }
                .joinToString(" ") { "runtime-classpath/${it.name}" },
        )
    }
}

// 依存を内包した単体実行可能 Fat JAR
tasks.shadowJar {
    archiveClassifier.set("standalone")
    manifest {
        attributes("Main-Class" to "com.hiyowa.tika.jtd.tools.OleTools")
    }
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}
