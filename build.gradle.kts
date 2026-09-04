import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "io.github.maximgromov"
version = "0.1.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Locally: build against the installed WebStorm (no IDE download).
        // On CI (or any machine without it): download the matching WebStorm build.
        val localIde = System.getenv("COMMENT_CLOAK_IDE")?.let(::file) ?: file("/Applications/WebStorm.app")
        if (localIde.exists()) local(localIde) else webstorm("2025.2.5")

        // Needed only so that tests can configure .ts / .js files.
        bundledPlugin("JavaScript")
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation("junit:junit:4.13.2")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

intellijPlatform {
    instrumentCode = false
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252"
            untilBuild = provider { null }
        }
    }
}

tasks.test {
    useJUnit()
    systemProperty("java.awt.headless", "true")
    systemProperty("idea.force.use.core.classloader", "true")
}
