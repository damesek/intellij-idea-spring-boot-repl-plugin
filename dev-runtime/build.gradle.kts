plugins {
    id("java")
}

group = "hu.baader"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.jar {
    manifest {
        attributes(
            mapOf(
                "Premain-Class" to "com.baader.devrt.Agent",
                "Agent-Class" to "com.baader.devrt.Agent",
                "Can-Redefine-Classes" to "true",
                "Can-Retransform-Classes" to "false"
            )
        )
    }
    archiveBaseName.set("dev-runtime-agent")
}
