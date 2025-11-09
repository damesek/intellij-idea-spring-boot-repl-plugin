#!/usr/bin/env bash
set -euo pipefail

echo "🚀 Starting IntelliJ IDEA with plugin in development mode..."

# Pick Gradle runner: prefer wrapper if JAR exists; otherwise fall back to system gradle
GRADLE_CMD=""
if [ -f "gradle/wrapper/gradle-wrapper.jar" ]; then
  GRADLE_CMD="./gradlew"
else
  if command -v gradle >/dev/null 2>&1; then
    echo "ℹ️ Gradle wrapper JAR missing. Using system Gradle."
    GRADLE_CMD="gradle"
    echo "ℹ️ Tip: after installing JDK 17, run 'gradle wrapper --gradle-version 8.5' to enable ./gradlew"
  else
    echo "❌ Gradle wrapper JAR missing and no system 'gradle' found."
    echo "   Install Gradle or run: brew install gradle  (macOS)"
    echo "   Then: gradle wrapper --gradle-version 8.5"
    exit 1
  fi
fi

# Ensure global gradle.properties with invalid flags cannot break the daemon
export ORG_GRADLE_JVMARGS="-Xmx2048m -Dfile.encoding=UTF-8 -XX:+HeapDumpOnOutOfMemoryError"

"$GRADLE_CMD" runIde

echo "Development IDE closed."
