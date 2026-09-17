#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
./gradlew check buildPlugin verifyPlugin "$@"
printf 'Installable plugin: %s/build/distributions/\n' "$PWD"
