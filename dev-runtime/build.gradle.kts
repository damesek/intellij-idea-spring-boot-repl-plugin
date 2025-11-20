plugins {
    id("java")
}

group = "hu.baader"
version = "0.8.0"

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
    compileOnly("org.slf4j:slf4j-api:2.0.9")

    // Add Byte Buddy for bytecode manipulation
    implementation("net.bytebuddy:byte-buddy:1.14.9")
    implementation("net.bytebuddy:byte-buddy-agent:1.14.9")

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
                "Can-Retransform-Classes" to "true"
            )
        )
    }
    archiveBaseName.set("dev-runtime-agent")
}
