plugins {
    kotlin("jvm") version "2.4.20" apply false
    id("com.gradleup.shadow") version "8.3.6" apply false
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
}

allprojects {
    group = providers.gradleProperty("group").get()
    version = providers.gradleProperty("version").get()
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")

    repositories {
        mavenCentral()
    }

    // 実行 JDK は 26 系でも、配布バイトコードは JVM 17 向け（Tika 4.0.0 の要求に合わせる）
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(17)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            showCauses = true
            showStackTraces = true
        }

        // 実データコーパス golden レール（opt-in・git 未追跡）。
        // gradle test -Pgatagate.corpus で sha256 1,204 行の全件照合を回す（MIGRATION.md §10.5 手順5）
        if (providers.gradleProperty("gatagate.corpus").isPresent) {
            systemProperty("gatagate.corpus", "true")
            systemProperty("gatagate.root", rootProject.projectDir.absolutePath)
        }
    }

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
    }
}
