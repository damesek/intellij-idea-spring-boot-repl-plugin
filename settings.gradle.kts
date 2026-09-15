pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "sb-repl"
include("dev-runtime")
include("repl-protocol")

include("sb-repl-bridge")
