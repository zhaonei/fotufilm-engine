#!/bin/sh
# 构建 Fotofilm X RAW 的 APK。
# 不依赖 gradle / AndroidX / aapt2：javac → d8 → 手写二进制 AndroidManifest → zip → apksigner。
#
#   ./build.sh          # → out/FotofilmXRAW.apk
#
# 可选环境变量：
#   JAVA_HOME / BUILD_TOOLS / ANDROID_JAR  （默认指向 ~/dl 下的 android-35 与 build-tools 35）
set -eu
cd "$(dirname "$0")"

JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/dl}}"
BT="${BUILD_TOOLS:-}"
PLATFORM="${ANDROID_JAR:-}"
if [ -z "$BT" ]; then
  BT=$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)
  [ -n "$BT" ] || BT="$HOME/dl/bt35/android-15"
fi
if [ -z "$PLATFORM" ]; then
  PLATFORM=$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)
  [ -n "$PLATFORM" ] || PLATFORM="$HOME/dl/plat35/android-35/android.jar"
fi
MIN_API=28
SIGN_MIN_API=24
OUT=out
STAGE="$OUT/stage"
APK_NAME=FotofilmXRAW.apk

[ -f "$PLATFORM" ] || { echo "缺少 android.jar: $PLATFORM" >&2; exit 1; }
[ -f "$BT/lib/d8.jar" ] || { echo "缺少 d8.jar: $BT/lib/d8.jar" >&2; exit 1; }

echo "== 1/5 javac (API 35, --release 11)"
rm -rf "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/classes" "$OUT/dex"
$JAVAC -encoding UTF-8 --release 11 -nowarn -Xlint:-options \
    -classpath "$PLATFORM" -d "$OUT/classes" \
    $(find app/src -name '*.java')

echo "== 2/5 d8 → classes.dex (min-api $MIN_API)"
$JAVA -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --release --min-api $MIN_API --lib "$PLATFORM" \
    --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "== 3/5 二进制 AndroidManifest.xml"
python3 tools/mkaxml.py app/AndroidManifest.xml "$OUT/AndroidManifest.xml"

echo "== 4/5 打包"
rm -rf "$STAGE"
mkdir -p "$STAGE"
cp "$OUT/AndroidManifest.xml" "$STAGE/"
cp "$OUT/dex/classes.dex" "$STAGE/"
rm -f "$OUT/$APK_NAME-unsigned.apk" "$OUT/$APK_NAME"
( cd "$STAGE" && zip -q -r -X "../$APK_NAME-unsigned.apk" . )

echo "== 5/5 签名 + 校验"
KS=debug.keystore
if [ ! -f "$KS" ]; then
  keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" -noprompt >/dev/null 2>&1
fi
$JAVA -jar "$BT/lib/apksigner.jar" sign \
    --ks "$KS" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --min-sdk-version $SIGN_MIN_API \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT/$APK_NAME" "$OUT/$APK_NAME-unsigned.apk"
# 关键：不带 --min-sdk-version，强制 apksigner 真正解析二进制 AndroidManifest
# （否则 manifest 结构错误无法被发现，装到手机上才会失败）
$JAVA -jar "$BT/lib/apksigner.jar" verify "$OUT/$APK_NAME"
$JAVA -jar "$BT/lib/apksigner.jar" verify --min-sdk-version $SIGN_MIN_API "$OUT/$APK_NAME"

echo
echo "built: $PWD/$OUT/$APK_NAME"
ls -l "$OUT/$APK_NAME"
