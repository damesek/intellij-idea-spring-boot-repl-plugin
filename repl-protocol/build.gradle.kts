plugins { java }
group = "hu.baader"
version = rootProject.version
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
