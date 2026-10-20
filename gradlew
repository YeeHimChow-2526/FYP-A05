#!/usr/bin/env bash
set -euo pipefail
if ! command -v java >/dev/null 2>&1 && [ -z "${JAVA_HOME:-}" ]; then
  STUDIO_JBR="/c/Program Files/Android/Android Studio/jbr"
  if [ -x "$STUDIO_JBR/bin/java" ]; then
    export JAVA_HOME="$STUDIO_JBR"
  fi
fi
exec "$(dirname "$0")/android/gradlew" -p "$(dirname "$0")/android" "$@"
