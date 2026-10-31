#!/usr/bin/env bash
# Builds the standalone "Teron Signals" native Android app (separate APK/package).
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
bash "$ROOT_DIR/scripts/build-apk.sh" signals app.teron.signals "releases/TeronSignals-v1.0.0.apk"
