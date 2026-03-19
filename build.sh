#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_ROOT="${ANDROID_SDK_ROOT:-/opt/android-sdk}"
BUILD_TOOLS_DIR="$SDK_ROOT/build-tools/34.0.0"
ANDROID_JAR="$SDK_ROOT/platforms/android-34/android.jar"

STAMP="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="$ROOT_DIR/build/$STAMP"
GEN_DIR="$OUT_DIR/gen"
CLASSES_DIR="$OUT_DIR/classes"
DEX_DIR="$OUT_DIR/dex"
DIST_DIR="$ROOT_DIR/dist"

mkdir -p "$OUT_DIR" "$GEN_DIR" "$CLASSES_DIR" "$DEX_DIR" "$DIST_DIR"

"$BUILD_TOOLS_DIR/aapt2" compile \
  --dir "$ROOT_DIR/res" \
  -o "$OUT_DIR/resources.zip"

"$BUILD_TOOLS_DIR/aapt2" link \
  -I "$ANDROID_JAR" \
  --manifest "$ROOT_DIR/AndroidManifest.xml" \
  --java "$GEN_DIR" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  -o "$OUT_DIR/QwenChat-unsigned-unaligned.apk" \
  "$OUT_DIR/resources.zip"

javac \
  -source 8 \
  -target 8 \
  -encoding UTF-8 \
  -bootclasspath "$ANDROID_JAR" \
  -classpath "$ANDROID_JAR" \
  -d "$CLASSES_DIR" \
  $(find "$ROOT_DIR/src" "$GEN_DIR" -name "*.java" | sort)

jar cf "$OUT_DIR/classes.jar" -C "$CLASSES_DIR" .

"$BUILD_TOOLS_DIR/d8" \
  --lib "$ANDROID_JAR" \
  --output "$DEX_DIR" \
  "$OUT_DIR/classes.jar"

(
  cd "$DEX_DIR"
  jar uf "$OUT_DIR/QwenChat-unsigned-unaligned.apk" classes.dex
)

"$BUILD_TOOLS_DIR/zipalign" -f 4 \
  "$OUT_DIR/QwenChat-unsigned-unaligned.apk" \
  "$OUT_DIR/QwenChat-unsigned.apk"

if [ ! -f "$ROOT_DIR/debug.keystore" ]; then
  keytool -genkeypair \
    -keystore "$ROOT_DIR/debug.keystore" \
    -storepass android \
    -keypass android \
    -alias androiddebugkey \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

"$BUILD_TOOLS_DIR/apksigner" sign \
  --ks "$ROOT_DIR/debug.keystore" \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$DIST_DIR/QwenChat.apk" \
  "$OUT_DIR/QwenChat-unsigned.apk"

"$BUILD_TOOLS_DIR/apksigner" verify --verbose "$DIST_DIR/QwenChat.apk"

echo
echo "Built APK:"
echo "$DIST_DIR/QwenChat.apk"
