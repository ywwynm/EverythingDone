#!/usr/bin/env sh
set -eu

ROOT="${1:-/var/www/everythingdone-updates}"

mkdir -p "$ROOT/debug/apk"
chmod 755 "$ROOT" "$ROOT/debug" "$ROOT/debug/apk"

echo "Prepared $ROOT/debug/apk"
