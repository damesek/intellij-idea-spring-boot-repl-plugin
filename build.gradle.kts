plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij.platform") version "2.0.1"
}

group = "hu.baader"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1.4")
        bundledPlugin("com.intellij.java")
        instrumentationTools()
    }
    
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
}

kotlin {
    jvmToolchain(17)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Java over nREPL"
        id = "hu.baader.java-over-nrepl"
        version = "1.0.0"
        vendor {
            name = "Baader"
        }
        description = """
            Execute Java code through nREPL in IntelliJ IDEA.
            Features:
            - Connect to nREPL server
            - Execute Java code snippets
            - Persistent settings
            - Tool window with REPL interface
        """.trimIndent()

        ideaVersion {
            sinceBuild = "241.0"
            untilBuild = "251.*"  // Support up to 2025.1
        }
    }
}

// Disable bundled Gradle plugin in the Development IDE to avoid GradleJvmSupportMatrix errors
// in network-restricted environments or with nonstandard JDKs.
tasks.named("runIde").configure {
    (this as org.gradle.api.tasks.JavaExec).apply {
        val current = jvmArgs ?: listOf()
        jvmArgs = current + listOf("-Didea.plugins.disabled=com.intellij.gradle")
    }
}
