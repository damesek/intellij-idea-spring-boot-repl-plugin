plugins { java }
group = "hu.baader"
version = rootProject.version
repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
dependencies {
    compileOnly("org.springframework.boot:spring-boot-autoconfigure:3.2.5")
    compileOnly("org.slf4j:slf4j-api:2.0.12")
}
