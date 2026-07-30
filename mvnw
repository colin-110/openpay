#!/bin/sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_DIR="$BASE_DIR/.mvn/wrapper"
DIST_URL="https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.9/apache-maven-3.9.9-bin.zip"
MAVEN_HOME="$WRAPPER_DIR/apache-maven-3.9.9"
ARCHIVE="$WRAPPER_DIR/apache-maven-3.9.9-bin.zip"

if [ ! -d "$MAVEN_HOME" ]; then
  mkdir -p "$WRAPPER_DIR"
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$DIST_URL" -o "$ARCHIVE"
  elif command -v wget >/dev/null 2>&1; then
    wget -q "$DIST_URL" -O "$ARCHIVE"
  else
    echo "curl or wget is required to download Maven." >&2
    exit 1
  fi

  if command -v unzip >/dev/null 2>&1; then
    unzip -q "$ARCHIVE" -d "$WRAPPER_DIR"
  else
    echo "unzip is required to extract Maven." >&2
    exit 1
  fi
fi

exec "$MAVEN_HOME/bin/mvn" "$@"
