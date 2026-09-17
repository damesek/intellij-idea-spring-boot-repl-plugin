plugins {
    id("java")
}

group = "hu.baader"
version = rootProject.version

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(project(":repl-protocol"))

    // Add Byte Buddy for bytecode manipulation
    implementation("net.bytebuddy:byte-buddy:1.14.9")
    implementation("net.bytebuddy:byte-buddy-agent:1.14.9")

    testImplementation(project(":sb-repl-bridge"))
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.springframework.boot:spring-boot-starter:3.5.6")
    testImplementation("org.springframework:spring-jdbc:6.2.11")
    testImplementation("org.springframework.boot:spring-boot-starter-web:3.5.6")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa:3.5.6")
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.15.3")
    testImplementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.15.3")
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework.boot:spring-boot-starter-test:3.5.6")
    testRuntimeOnly("org.springframework.boot:spring-boot-loader:3.5.6")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("sb.repl.audit.dir", layout.buildDirectory.dir("test-audit").get().asFile.absolutePath)
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("testJdk").getOrElse("17").toInt()))
    })
    dependsOn(tasks.jar)
    systemProperty("sb.repl.agentJar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}

tasks.jar {
    dependsOn(":repl-protocol:jar")
    from({ zipTree(project(":repl-protocol").tasks.named<Jar>("jar").get().archiveFile.get().asFile) }) {
        exclude("META-INF/MANIFEST.MF")
    }
    // Instrumentation dependencies are loaded by an isolated loader, not the application's loader.
    into("agent-libs") {
        from(configurations.runtimeClasspath.map { files -> files.filter { it.name.startsWith("byte-buddy-") } })
    }
    manifest {
        attributes(
            mapOf(
                "SB-Repl-Protocol" to "1",
                "Premain-Class" to "com.baader.devrt.Agent",
                "Agent-Class" to "com.baader.devrt.Agent",
                "Can-Redefine-Classes" to "true",
                "Can-Retransform-Classes" to "true"
            )
        )
    }
    archiveBaseName.set("dev-runtime-agent")
}

// Ship the portable assertion source for ordinary JUnit exports.
tasks.processResources {
    from(listOf("src/main/java/com/baader/devrt/CaseAssertions.java", "src/main/java/com/baader/devrt/CaseSqlCounter.java", "src/main/java/com/baader/devrt/CaseHibernateProbe.java", "../repl-protocol/src/main/java/hu/baader/repl/protocol/SqlText.java")) { into("case-export") }
}
