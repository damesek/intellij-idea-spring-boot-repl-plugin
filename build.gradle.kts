import org.gradle.api.tasks.Copy
import org.gradle.jvm.tasks.Jar

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij.platform") version "2.0.1"
}

group = "hu.baader"
version = "0.8.0"

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
}

// Bundle the dev-runtime agent into the plugin for zero-config attach.
val bundledAgentDir = layout.buildDirectory.dir("generated/bundledAgent")
val copyDevRuntimeAgent = tasks.register<Copy>("copyDevRuntimeAgent") {
    dependsOn(project(":dev-runtime").tasks.named<Jar>("jar"))
    from(project(":dev-runtime").tasks.named<Jar>("jar").map { it.archiveFile }) {
        into("agent")
        rename { "dev-runtime-agent.jar" }
    }
    into(bundledAgentDir)
}

sourceSets.main {
    resources.srcDir(bundledAgentDir)
    resources.srcDir("docs")
}

kotlin {
    jvmToolchain(17)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.processResources {
    dependsOn(copyDevRuntimeAgent)
}

intellijPlatform {
    pluginConfiguration {
        name = "Spring Boot REPL"
        id = "hu.baader.java-over-nrepl"
        version = "0.8.0"
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
            
            Getting started:
            1. Create a Spring Boot REPL run configuration and point it to your Spring Boot main class (no extra dependencies required).
            2. Run the configuration; the bundled dev-runtime agent attaches automatically and starts an in-process nREPL server.
            3. Open the Spring Boot REPL tool window; the plugin auto-connects and binds Spring context so 'ctx' is available immediately.
            4. Use the jREPL tab to evaluate Java against the live ctx, and the Snapshots / HTTP tabs to persist values and exercise REST endpoints.
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
