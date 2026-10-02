import org.gradle.api.tasks.Copy
import org.gradle.jvm.tasks.Jar
import java.security.MessageDigest

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "1.9.21"
    id("org.jetbrains.intellij.platform") version "2.0.1"
}

group = "hu.baader"
version = "0.25.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation(project(":repl-protocol"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(project(":dev-runtime"))
    // MCP integration tests exercise a real Spring context and DATA snapshots, without bundling them in the plugin.
    testImplementation("org.springframework.boot:spring-boot-starter:3.5.6")
    testRuntimeOnly("com.fasterxml.jackson.core:jackson-databind:2.15.3")
    intellijPlatform {
        // Compile against the oldest supported IDE; verify newer IDEs below.
        intellijIdeaCommunity("2024.1.4")
        bundledPlugin("com.intellij.java")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        instrumentationTools()
        pluginVerifier()
    }
}

// Bundle the dev-runtime agent into the plugin for zero-config attach.
val bundledAgentDir = layout.buildDirectory.dir("generated/bundledAgent")
val copyDevRuntimeAgent = tasks.register<Copy>("copyDevRuntimeAgent") {
    dependsOn(":dev-runtime:jar")
    from({ project(":dev-runtime").tasks.named<Jar>("jar").get().archiveFile.get().asFile }) {
        into("agent")
        rename { "dev-runtime-agent.jar" }
    }
    into(bundledAgentDir)
}

sourceSets.main {
    resources.srcDir(bundledAgentDir)
}

allprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    plugins.withId("java") {
        tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
            from(rootProject.files("LICENSE", "NOTICE")) { into("META-INF") }
        }
    }
}

val verifyModuleVersions by tasks.registering {
    group = "verification"
    description = "Prevent release version drift between the plugin and Maven runtime/bridge packages."
    inputs.property("pluginVersion", project.version.toString())
    val poms = listOf("sb-repl-agent/pom.xml", "sb-repl-bridge/pom.xml")
    inputs.files(poms)
    doLast {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        for (pom in poms) {
            val document = factory.newDocumentBuilder().parse(file(pom))
            val actual = document.documentElement.getElementsByTagName("version").item(0).textContent.trim()
            check(actual == project.version.toString()) { "$pom declares $actual; expected ${project.version}" }
        }
    }
}

kotlin {
    jvmToolchain(17)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

val helpLanguages = listOf("hu", "en")
val verifyBundledHelp by tasks.registering {
    group = "verification"
    description = "Verify that both offline manuals match their sources and repository copies."
    inputs.property("pluginVersion", project.version.toString())
    for (language in helpLanguages) {
        inputs.file("docs/repl-help-$language.md")
        inputs.file("output/pdf/spring-boot-repl-guide-$language.pdf")
        inputs.file("src/main/resources/help/spring-boot-repl-guide-$language.pdf")
        inputs.file("src/main/resources/help/spring-boot-repl-guide-$language.source.sha256")
    }
    doLast {
        for (language in helpLanguages) {
            val source = file("docs/repl-help-$language.md").readText(Charsets.UTF_8)
                .replace("{{version}}", project.version.toString())
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(source.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            check(file("src/main/resources/help/spring-boot-repl-guide-$language.source.sha256").readText().trim() == digest) {
                "The $language manual is stale. Run python3 scripts/build-help-pdf.py."
            }
            val bundled = file("src/main/resources/help/spring-boot-repl-guide-$language.pdf").readBytes()
            check(bundled.size > 5 && String(bundled, 0, 5, Charsets.US_ASCII) == "%PDF-") { "Invalid $language PDF" }
            check(bundled.contentEquals(file("output/pdf/spring-boot-repl-guide-$language.pdf").readBytes())) {
                "The bundled and repository $language PDFs differ. Run python3 scripts/build-help-pdf.py."
            }
        }
    }
}

tasks.processResources {
    dependsOn(copyDevRuntimeAgent, verifyBundledHelp)
    from(listOf("PRIVACY.md", "THIRD-PARTY-NOTICES.txt")) { into("META-INF") }
}

intellijPlatform {
    pluginConfiguration {
        name = "Spring Boot Debug REPL and MCP"
        id = "hu.baader.java-over-nrepl"
        version = project.version.toString()
        vendor {
            name = "Baader"
        }
        changeNotes = """
            <p>0.25.0</p>
            <ul>
              <li>Focus the interface on Workbook and MCP; keep all supporting panels in Tools.</li>
              <li>Keep the complete 82-tool MCP catalog and permissions; reveal advanced settings on demand.</li>
              <li>Connect purple source snapshot points to the Java debugger so they can save DATA when hit.</li>
              <li>Refresh both offline manuals, the scalable icon and data-handling documentation.</li>
              <li>License the project under EPL 2.0 and include license/source notices in distributions.</li>
            </ul>
            <p>0.24.1</p>
            <ul>
              <li>Remember the MCP listening port per project and retain its access token in IDE PasswordSafe.</li>
              <li>Keep copied client configurations usable across server and IDE restarts; client sessions remain temporary.</li>
            </ul>
            <p>0.24.0</p>
            <ul>
              <li>Renamed to Spring Boot Debug REPL and MCP while preserving settings and configuration IDs.</li>
              <li>Improved session cleanup and CASE result retention after rejected edits.</li>
              <li>Separated runtime, MCP and HTTP responsibilities and removed obsolete code and packaging assets.</li>
              <li>Updated the concise README and bundled English and Hungarian manuals.</li>
            </ul>
        """.trimIndent()

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = providers.gradleProperty("pluginUntilBuild")
        }
    }
}

// A user-triggered buildPlugin includes the regression suite.
tasks.named("check") {
    dependsOn(verifyModuleVersions, ":dev-runtime:check", ":repl-protocol:check", ":sb-repl-bridge:check")
}
tasks.named<Zip>("buildPlugin") {
    dependsOn(tasks.named("check"))
    from(files("LICENSE", "NOTICE", "THIRD-PARTY-NOTICES.txt"))
}
val testJavaLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("testJdk").getOrElse("17").toInt()))
}
val jshellIntegrationTests = listOf(
    "hu/baader/repl/editor/ReplCellsTest.class",
    "hu/baader/repl/editor/SnapshotPointExpressionTest.class",
    "hu/baader/repl/ui/HttpSnippetBuilderTest.class",
    "hu/baader/repl/mcp/McpIntegrationTest.class"
)
// JShell runs in the application's ordinary JVM. IDEA's PathClassLoader hides
// its JDK compiler service, so exercise these integrations in a separate worker.
val jshellIntegrationTest by tasks.registering(Test::class) {
    group = "verification"
    description = "Tests plugin-to-runtime integration with the full JDK compiler."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath + sourceSets.test.get().compileClasspath
    include(jshellIntegrationTests)
    systemProperty("java.awt.headless", "true")
    systemProperty("sb.repl.audit.dir", layout.buildDirectory.dir("test-audit").get().asFile.absolutePath)
    maxHeapSize = "512m"
    javaLauncher.set(testJavaLauncher)
}
tasks.test {
    dependsOn(jshellIntegrationTest)
    exclude(jshellIntegrationTests)
    systemProperty("java.awt.headless", "true")
    systemProperty("sb.repl.audit.dir", layout.buildDirectory.dir("test-audit").get().asFile.absolutePath)
    systemProperty("sb.repl.uiRenderDir", layout.buildDirectory.dir("ui-safety").get().asFile.absolutePath)
    javaLauncher.set(testJavaLauncher)
}

intellijPlatform {
    pluginVerification {
        val verifyPreviousIdes = providers.gradleProperty("verifyPreviousIdes").getOrElse("false").toBoolean()
        verificationReportsDirectory = layout.buildDirectory.dir(
            "reports/pluginVerifier/" + if (verifyPreviousIdes) "previous" else "2025.2"
        )
        ides {
            // Separate runs avoid Gradle resolving two versions of the same
            // product dependency to only the newest SDK in this tooling version.
            if (verifyPreviousIdes) {
                ide("IC", "2024.1.4")
                ide("IU", "2025.1.7")
            } else {
                ide("IC", "2025.2")
                ide("IU", "2025.2")
            }
        }
    }
}
