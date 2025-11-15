#!/usr/bin/env bash
set -euo pipefail

echo "🔨 Building Java over nREPL plugin..."

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

echo "Cleaning previous builds..."
"$GRADLE_CMD" clean

echo "Building plugin..."
"$GRADLE_CMD" buildPlugin

echo "✅ Build successful!"
echo "📦 Plugin package(s):"
ls -1 build/distributions/*.zip 2>/dev/null || echo "(no distribution ZIP produced)"
echo ""
echo "To install in IntelliJ IDEA:"
echo "1. Settings → Plugins → ⚙️ → Install Plugin from Disk"
echo "2. Select the .zip file from build/distributions/"
echo "3. Restart IDE"
