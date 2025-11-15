plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij.platform") version "2.0.1"
}

group = "hu.baader"
version = "0.7.2"

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
        name = "Spring Boot REPL"
        id = "hu.baader.java-over-nrepl"
        version = "0.7.2"
        vendor {
            name = "Baader"
        }
        description = """
            Spring Boot REPL for IntelliJ IDEA.
            Features:
            - Attach a lightweight dev-runtime agent to a running Spring Boot JVM
            - JShell-based Java REPL with stateful imports and variables
            - Automatic Spring context binding and ctx helper
            - Hot-swap support for editing and reloading classes
            - Editor actions for Run Selection, Evaluate at Caret, Reload Class, Sync Imports
            - Optional HTTP panel for managing and replaying REST calls
        """.trimIndent()

        ideaVersion {
            sinceBuild = "241.0"
            untilBuild = "252.*"  // Support up to 2025.2
        }
    }
}

// Disable bundled Gradle plugin in the Development IDE to avoid GradleJvmSupportMatrix errors
// in network-restricted environments or with nonstandard JDKs.
tasks.named("runIde").configure {
    (this as org.gradle.api.tasks.JavaExec).apply {
        val current = jvmArgs ?: listOf()
        // Disable Gradle plugin in sandbox IDE to avoid GradleJvmSupportMatrix errors
        jvmArgs = current + listOf(
            "-Didea.plugins.disabled=com.intellij.gradle,org.jetbrains.plugins.gradle"
        )
    }
}
