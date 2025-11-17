#!/usr/bin/env bash

            set -euo pipefail

            mvn -f sb-repl-bridge/pom.xml clean deploy \
              -Dgpg.keyname="$GPG_KEYNAME" \
              -Dgpg.passphrase="$GPG_PASSPHRASE"

            mvn -f sb-repl-agent/pom.xml clean deploy \
              -Dgpg.keyname="$GPG_KEYNAME" \
              -Dgpg.passphrase="$GPG_PASSPHRASE"
