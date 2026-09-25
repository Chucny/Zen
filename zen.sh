#!/bin/sh
# zen.sh - run the Zen build tool without typing "python3 zen.py".
# Detects a bundled toolchain next to zen.py (or one folder up) and
# sets ZEN_JAVA / ZEN_GRADLE / ZEN_SDK only if you have not set them.
# Usage:  zen <command> [args...]   e.g.  zen build app.zip --name "My App"
ZEN_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ZEN_PY="$ZEN_DIR/zen.py"
TOOLCHAIN=""

[ -d "$ZEN_DIR/toolchain" ] && TOOLCHAIN="$ZEN_DIR/toolchain"
[ -z "$TOOLCHAIN" ] && [ -d "$ZEN_DIR/../toolchain" ] && TOOLCHAIN="$ZEN_DIR/../toolchain"

if [ -n "$TOOLCHAIN" ]; then
  if [ -z "$ZEN_JAVA" ]; then
    for d in "$TOOLCHAIN"/jdk*; do [ -d "$d" ] && ZEN_JAVA="$d" && break; done
  fi
  if [ -z "$ZEN_GRADLE" ]; then
    if [ -d "$TOOLCHAIN/gradle-8.9" ]; then
      ZEN_GRADLE="$TOOLCHAIN/gradle-8.9"
    else
      for d in "$TOOLCHAIN"/gradle*; do [ -d "$d" ] && ZEN_GRADLE="$d" && break; done
    fi
  fi
  [ -z "$ZEN_SDK" ] && [ -d "$TOOLCHAIN/sdk" ] && ZEN_SDK="$TOOLCHAIN/sdk"
  export ZEN_JAVA ZEN_GRADLE ZEN_SDK
fi

if command -v python3 >/dev/null 2>&1; then
  exec python3 "$ZEN_PY" "$@"
elif command -v python >/dev/null 2>&1; then
  exec python "$ZEN_PY" "$@"
else
  echo "[zen] python3 not found" >&2
  exit 1
fi