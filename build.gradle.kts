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

        // §12.5 回帰評価セット（opt-in・ground truth 骨格照合・git 未追跡）。
        // gradle test -Pflowstruct.rail で tr/td/colspan/rowspan 骨格トライブ照合を回す（本文非開示）。
        if (providers.gradleProperty("flowstruct.rail").isPresent) {
            systemProperty("flowstruct.rail", "true")
            systemProperty("flowstruct.root", rootProject.projectDir.absolutePath)
        }

        // RFC 0013 追補 §13 縦継続セル ground truth レール（opt-in・骨格のみ・本文非開示）。
        // gradle test -Ptallrow.rail で縦統合（tall cell）骨格トライブ照合を回す。
        if (providers.gradleProperty("tallrow.rail").isPresent) {
            systemProperty("tallrow.rail", "true")
            systemProperty("tallrow.root", rootProject.projectDir.absolutePath)
        }

        // RFC 0013 追補 §20 罫ボックス save 往復 rail（opt-in・意味イベントのみ・本文非開示）。
        // gradle test -Prulebox.rail で保存往復不变性＋辺幅 ground truth 照合を回す。
        if (providers.gradleProperty("rulebox.rail").isPresent) {
            systemProperty("rulebox.rail", "true")
            systemProperty("rulebox.root", rootProject.projectDir.absolutePath)
            providers.gradleProperty("rulebox.dir").orNull?.let {
                systemProperty("rulebox.dir", rootProject.file(it).absolutePath)
            }
        }
    }

    extensions.configure<JavaPluginExtension> {
        withSourcesJar()
    }
}
