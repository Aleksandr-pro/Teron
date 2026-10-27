#!/usr/bin/env bash
set -euo pipefail

TC="/tmp/android-toolchain"
if [ -f "$TC/lib/kotlin-stdlib-dex.zip" ] && [ -f "$TC/lib/apksigner.jar" ] && [ -x "$TC/bin/aapt2" ]; then
  echo "[toolchain] Android native toolchain already ready in $TC"
  exit 0
fi

echo "[toolchain] Initializing Android SDK 34 + Kotlin 1.9.25 toolchain in $TC..."
mkdir -p "$TC"/{bin,lib,lib64}

pip install --quiet --target "$TC/pyjdk" jdk4py==21.0.8.2
JAVA_HOME=$(python3 -c "import sys; sys.path.insert(0,'$TC/pyjdk'); import jdk4py; print(jdk4py.JAVA_HOME)")
ln -sf "$JAVA_HOME/bin/java" "$TC/bin/java"
ln -sf "$JAVA_HOME/bin/keytool" "$TC/bin/keytool"

npm install --silent --prefix "$TC/npm" kotlin-compiler@1.9.25

# Android 34 SDK android.jar (Sable/android-platforms)
gh api repos/Sable/android-platforms/git/blobs/923bafccf73aef996495c559e919ad8be3cabd58 \
  -H "Accept: application/vnd.github.raw" > "$TC/lib/android-34.jar"

# Linux x86_64 aapt2 (iBotPeaches/Apktool)
gh api repos/iBotPeaches/Apktool/git/blobs/9327b68672020608699a49f36878190c67f1d762 \
  -H "Accept: application/vnd.github.raw" > "$TC/bin/aapt2"
chmod +x "$TC/bin/aapt2"

# Linux x86_64 zipalign + shared C++ runtime (patrickfav/uber-apk-signer)
gh api repos/patrickfav/uber-apk-signer/git/blobs/9538c28030451473660ec1fa8ed48191db3066ad \
  -H "Accept: application/vnd.github.raw" > "$TC/bin/zipalign-bin"
chmod +x "$TC/bin/zipalign-bin"

for item in $(gh api repos/patrickfav/uber-apk-signer/contents/src/main/resources/binary-lib/linux-lib64-33_0_2 --jq '.[] | "\(.name):\(.sha)"'); do
  name="${item%%:*}"
  sha="${item##*:}"
  gh api "repos/patrickfav/uber-apk-signer/git/blobs/$sha" -H "Accept: application/vnd.github.raw" > "$TC/lib64/$name"
done

cat > "$TC/bin/zipalign" << 'EOF'
#!/usr/bin/env bash
LD_LIBRARY_PATH="/tmp/android-toolchain/lib64:${LD_LIBRARY_PATH:-}" exec /tmp/android-toolchain/bin/zipalign-bin "$@"
EOF
chmod +x "$TC/bin/zipalign"

# Google D8 DEX compiler (ReversecLabs/drozer)
gh api repos/ReversecLabs/drozer/git/blobs/5fcaa4f6a84f0947498a08c68882a6e6b18f19ea \
  -H "Accept: application/vnd.github.raw" > "$TC/lib/d8.jar"

# Google apksigner (warren-bank/print-apk-signature)
gh api repos/warren-bank/print-apk-signature/git/blobs/5b38de849a1c655bb4c776ec1c1a9e5f4ea3f8f7 \
  -H "Accept: application/vnd.github.raw" > "$TC/lib/apksigner.jar"

# Pre-dex kotlin-stdlib once for fast incremental builds
"$TC/bin/java" -cp "$TC/lib/d8.jar" com.android.tools.r8.D8 \
  --min-api 24 \
  --lib "$TC/lib/android-34.jar" \
  --output "$TC/lib/kotlin-stdlib-dex.zip" \
  "$TC/npm/node_modules/kotlin-compiler/lib/kotlin-stdlib.jar"

echo "[toolchain] Ready!"
