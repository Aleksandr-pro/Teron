#!/usr/bin/env bash
# Builds the standalone "Teron Signals" native Android app (separate APK/package).
# Output name is derived from versionName in the signals AndroidManifest,
# so the APK file, the in-app update checker and TeronSignals-version.json
# can never drift apart. Always signed with the SAME permanent release key
# (keystore/teron-release.keystore) — updates install over previous versions.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VERSION_NAME=$(python3 -c "import re; m=re.search(r'versionName=\"([^\"]+)\"', open('$ROOT_DIR/signals/src/main/AndroidManifest.xml').read()); print(m.group(1))")
bash "$ROOT_DIR/scripts/build-apk.sh" signals app.teron.signals "releases/TeronSignals-v${VERSION_NAME}.apk"
echo "DOWNLOAD_URL: https://raw.githubusercontent.com/Aleksandr-pro/Teron/main/releases/TeronSignals-v${VERSION_NAME}.apk"
