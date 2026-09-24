dependencies {
    implementation(project(":tika-parser-jtd"))

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
