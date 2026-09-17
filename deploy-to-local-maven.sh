#!/usr/bin/env bash
# Installs only into the local Maven repository; never publishes remotely.
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
./gradlew verifyModuleVersions
mvn -B -f sb-repl-agent/pom.xml install -Dgpg.skip=true "$@"
mvn -B -f sb-repl-bridge/pom.xml install -Dgpg.skip=true "$@"
