plugins {
    id("java")
}

group = "hu.baader"
version = "0.7.5"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    compileOnly("org.springframework:spring-context:6.0.13")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    useJUnitPlatform()
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
