#!/bin/bash
# 手工构建 APK（不依赖 Gradle / AndroidX）：
#   aapt2 link 资源+manifest  ->  javac  ->  d8  ->  zipalign  ->  apksigner
#
# 用法: ./build_apk.sh <项目目录>      # 项目目录里要有 app/src/main/{AndroidManifest.xml,java/}
# 产物: <项目目录>/<目录名>.apk
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"          # vpncommon/
PROJ="$(cd "${1:-.}" && pwd)"
NAME="$(basename "$PROJ")"
APK_NAME="$NAME.apk"
KEYSTORE="$HERE/keystore.jks"
KEY_ALIAS="vpnswitch"
KEY_PASS="vpnswitch"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export PATH="$JAVA_HOME/bin:$PATH"

BT="$ANDROID_HOME/build-tools/35.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-35/android.jar"
[ -x "$BT/aapt2" ] || { echo "找不到 build-tools: $BT"; exit 1; }
[ -f "$PLATFORM" ] || { echo "找不到 android.jar: $PLATFORM"; exit 1; }

MIN_SDK=24
TARGET_SDK=34
OUT="$PROJ/build"

cd "$PROJ"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex"

echo "==> [$NAME] aapt2 link"
"$BT/aapt2" link \
    -o "$OUT/base.ap_" \
    -I "$PLATFORM" \
    --manifest app/src/main/AndroidManifest.xml \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code 1 --version-name 1.0

echo "==> [$NAME] javac"
{ find app/src/main/java -name '*.java'; find "$HERE/com" -name '*.java'; } > "$OUT/sources.txt"
"$JAVA_HOME/bin/javac" -source 17 -target 17 -encoding UTF-8 -Xlint:-options \
    -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> [$NAME] d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --lib "$PLATFORM" --min-api "$MIN_SDK" --output "$OUT/dex" @"$OUT/classes.txt"

echo "==> [$NAME] package"
cp "$OUT/base.ap_" "$OUT/unsigned.apk"
( cd "$OUT/dex" && zip -q -X "../unsigned.apk" classes.dex )

echo "==> [$NAME] zipalign"
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f "$KEYSTORE" ]; then
    echo "==> 生成签名证书 $KEYSTORE"
    "$JAVA_HOME/bin/keytool" -genkeypair -v \
        -keystore "$KEYSTORE" -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10950 \
        -storepass "$KEY_PASS" -keypass "$KEY_PASS" \
        -dname "CN=vpnswitch, O=local, C=CN" >/dev/null 2>&1
fi

echo "==> [$NAME] apksigner"
"$BT/apksigner" sign \
    --ks "$KEYSTORE" --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEY_PASS" --key-pass "pass:$KEY_PASS" \
    --out "$APK_NAME" "$OUT/aligned.apk"
rm -f "$APK_NAME.idsig"

echo "==> 完成: $PROJ/$APK_NAME"
ls -lh "$APK_NAME" | awk '{print $5, $9}'
