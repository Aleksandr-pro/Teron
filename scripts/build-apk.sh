#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
bash "$ROOT_DIR/scripts/setup-toolchain.sh"

# ---------------------------------------------------------------------------
# Generalized native Android build (100% Kotlin, no Gradle, no AndroidX).
#
#   bash scripts/build-apk.sh [module] [package] [output.apk]
#
# Defaults reproduce the original Teron super-app build exactly.
#   bash scripts/build-apk.sh                 -> app / app.teron.superapp
#   bash scripts/build-apk.sh signals app.teron.signals releases/TeronSignals-v1.0.0.apk
# ---------------------------------------------------------------------------
MODULE="${1:-app}"
PKG="${2:-app.teron.superapp}"
OUT_FILE="${3:-}"
export PKG

TC="/tmp/android-toolchain"
JAVA_HOME=$(python3 -c "import sys; sys.path.insert(0,'$TC/pyjdk'); import jdk4py; print(jdk4py.JAVA_HOME)")
export JAVA_HOME
export PATH="$TC/bin:$JAVA_HOME/bin:$PATH"

MANIFEST="$ROOT_DIR/$MODULE/src/main/AndroidManifest.xml"
RES_DIR="$ROOT_DIR/$MODULE/src/main/res"
SRC_DIR="$ROOT_DIR/$MODULE/src/main/kotlin"

VERSION_NAME=$(python3 -c "import re; m=re.search(r'versionName=\"([^\"]+)\"', open('$MANIFEST').read()); print(m.group(1))")

if [ -n "$OUT_FILE" ]; then
  case "$OUT_FILE" in
    /*) OUT_APK="$OUT_FILE" ;;
    *)  OUT_APK="$ROOT_DIR/$OUT_FILE" ;;
  esac
else
  OUT_APK="$ROOT_DIR/releases/Teron-v${VERSION_NAME}.apk"
fi

BUILD_DIR="/tmp/teron-apk-build"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$ROOT_DIR/releases"

echo "==> [1/5] Compiling Android resources with aapt2 ($PKG v$VERSION_NAME)..."
aapt2 compile --dir "$RES_DIR" -o "$BUILD_DIR/res.zip"
aapt2 link \
  -I "$TC/lib/android-34.jar" \
  --manifest "$MANIFEST" \
  --output-text-symbols "$BUILD_DIR/R.txt" \
  -o "$BUILD_DIR/unsigned.apk" \
  "$BUILD_DIR/res.zip"

python3 - << PY
from collections import defaultdict
import os
groups = defaultdict(list)
with open("/tmp/teron-apk-build/R.txt") as f:
    for line in f:
        parts = line.strip().split()
        if len(parts) == 4 and parts[0] == "int":
            _, rtype, name, val = parts
            groups[rtype].append((name, val))
pkg = os.environ["PKG"]
with open("/tmp/teron-apk-build/R.kt", "w") as out:
    out.write("package %s\nobject R {\n" % pkg)
    for rtype, items in sorted(groups.items()):
        out.write("    object \`%s\` {\n" % rtype)
        for name, val in items:
            out.write("        const val \`%s\`: Int = %s\n" % (name, val))
        out.write("    }\n")
    out.write("}\n")
PY

echo "==> [2/5] Compiling native Kotlin sources with kotlinc..."
KT_SOURCES=$(find "$SRC_DIR" -name "*.kt" | sort)
"$TC/npm/node_modules/kotlin-compiler/bin/kotlinc" \
  -J-Xmx2048m \
  $KT_SOURCES \
  "$BUILD_DIR/R.kt" \
  -classpath "$TC/lib/android-34.jar" \
  -jvm-target 11 \
  -d "$BUILD_DIR/classes"

# Safety check: ensure ZERO WebView usage anywhere in bytecode!
if grep -rn "android/webkit/WebView" "$BUILD_DIR/classes" >/dev/null 2>&1; then
  echo "ERROR: WebView detected! Teron must be 100% native!"
  exit 1
fi

echo "==> [3/5] Compiling JVM bytecode to Dalvik/ART DEX with Google D8..."
mkdir -p "$BUILD_DIR/dex"
java -cp "$TC/lib/d8.jar" com.android.tools.r8.D8 \
  --min-api 24 \
  --lib "$TC/lib/android-34.jar" \
  --output "$BUILD_DIR/dex" \
  $(find "$BUILD_DIR/classes" -name "*.class") \
  "$TC/lib/kotlin-stdlib-dex.zip"

python3 - << PY
import glob, os, zipfile
with zipfile.ZipFile("/tmp/teron-apk-build/unsigned.apk", "a", compression=zipfile.ZIP_DEFLATED) as zf:
    for dex_path in sorted(glob.glob("/tmp/teron-apk-build/dex/classes*.dex")):
        zf.write(dex_path, os.path.basename(dex_path))
PY

echo "==> [4/5] Aligning APK on 4-byte page boundaries with zipalign..."
zipalign -f -p 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"

rm -f "$OUT_APK"

echo "==> [5/5] Signing APK (Scheme v1 + v2 + v3) & verifying signature..."
java -jar "$TC/lib/apksigner.jar" sign \
  --ks "$ROOT_DIR/keystore/teron-release.keystore" \
  --ks-pass pass:teron_superapp_2026 \
  --key-pass pass:teron_superapp_2026 \
  --ks-key-alias teron_release \
  --min-sdk-version 21 \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --out "$OUT_APK" \
  "$BUILD_DIR/aligned.apk"

java -jar "$TC/lib/apksigner.jar" verify --verbose "$OUT_APK"
ls -lh "$OUT_APK"
echo "SUCCESS: Built $OUT_APK"
