#!/bin/sh
# Builds FotufilmShell.apk from this checkout.
#
#   1. libfotufilm.so   arm64, with the JNI bridge and whatever engine is linked in
#   2. classes.dex      the Java shell, through d8
#   3. AndroidManifest  binary XML, written by tools/mkaxml.py (the SDK's aapt2 is x86-64 only)
#   4. package + sign   apksigner
#
#   ANDROID_NDK_HOME=… ./build.sh
#   FOTUFILM_REAL_ENGINE=/path/to/libfotufilm.so ./build.sh   # the genuine engine, same ABI
#
# Needs: clang, lld, cmake+ninja (or set FOTUFILM_DIRECT_CLANG=1), JDK 17, python3, zip, and the
# SDK's build-tools jars (d8, apksigner) plus a platform android.jar -- see README.md.
set -eu
cd "$(dirname "$0")/.."        # the repository root: Sources/CFotufilmHost lives there
ROOT="$PWD"
SHELL_DIR="$ROOT/android-shell"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an unpacked Android NDK}"
JDK="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
BT="${BUILD_TOOLS:?set BUILD_TOOLS to the SDK build-tools directory (d8.jar, apksigner.jar)}"
PLATFORM="${ANDROID_JAR:?set ANDROID_JAR to the SDK platform android.jar}"
ABI=arm64-v8a
MIN_API=28
# Signing floor below the app's minSdk, so the APK carries v2 as well for older installers.
SIGN_MIN_API=24
OUT="$SHELL_DIR/out"
STAGE="$OUT/stage"
PREBUILT="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/ | head -1)"
SYSROOT="${PREBUILT%/}/sysroot"
LIBCXX="$SYSROOT/usr/lib/aarch64-linux-android"

echo "== 1/5 libfotufilm.so ($ABI, API $MIN_API)"
mkdir -p "$OUT/lib/$ABI"
if [ -n "${FOTUFILM_REAL_ENGINE:-}" ]; then
  cp "$FOTUFILM_REAL_ENGINE" "$OUT/lib/$ABI/libfotufilm.so"
  echo "   from $FOTUFILM_REAL_ENGINE"
elif [ -z "${FOTUFILM_DIRECT_CLANG:-}" ] && command -v cmake >/dev/null 2>&1; then
  TOOLCHAIN="$SHELL_DIR/cmake/host-aarch64-android28.cmake"
  [ -f "$NDK/build/cmake/android.toolchain.cmake" ] && [ "$(uname -m)" != aarch64 ] && \
    TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
  cmake -S "$SHELL_DIR" -B "$SHELL_DIR/build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-$MIN_API \
    -DCMAKE_BUILD_TYPE=Release >/dev/null
  cmake --build "$SHELL_DIR/build" --target fotufilm
  cp "$SHELL_DIR/build/libfotufilm.so" "$OUT/lib/$ABI/libfotufilm.so"
else
  "$SHELL_DIR/toolchain/aarch64-linux-android28-clang++" \
    -std=c++17 -O2 -fPIC -shared -nostdlib++ \
    -fvisibility=hidden -fvisibility-inlines-hidden \
    -ffunction-sections -fdata-sections -Wl,--gc-sections \
    -I"$ROOT/Sources/CFotufilmHost/include" -I"$JDK/include" -I"$JDK/include/linux" \
    -nostdinc++ -isystem "$SYSROOT/usr/include/c++/v1" \
    "$SHELL_DIR/jni/fotufilm_engine_stub.cpp" "$SHELL_DIR/jni/fotufilm_jni.cpp" \
    "$LIBCXX/libc++_static.a" "$LIBCXX/libc++abi.a" \
    -Wl,--exclude-libs,ALL -Wl,-soname,libfotufilm.so \
    -Wl,-z,max-page-size=16384 -Wl,--build-id=sha1 \
    -o "$OUT/lib/$ABI/libfotufilm.so"
fi

echo "== 2/5 classes.dex"
rm -rf "$OUT/classes" "$OUT/dex"
mkdir -p "$OUT/classes" "$OUT/dex"
"$JDK/bin/javac" -encoding UTF-8 --release 11 -nowarn -classpath "$PLATFORM" \
  -d "$OUT/classes" $(find "$SHELL_DIR/app/src" -name '*.java')
"$JDK/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
  --release --min-api $MIN_API --lib "$PLATFORM" \
  --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')

echo "== 3/5 AndroidManifest.xml (binary)"
python3 "$SHELL_DIR/tools/mkaxml.py" "$SHELL_DIR/app/AndroidManifest.xml" "$OUT/AndroidManifest.xml"

echo "== 4/5 package"
rm -rf "$STAGE"
mkdir -p "$STAGE/lib/$ABI"
cp "$OUT/AndroidManifest.xml" "$OUT/dex/classes.dex" "$STAGE/"
cp "$OUT/lib/$ABI/libfotufilm.so" "$STAGE/lib/$ABI/"
# The genuine engine rides with its Swift runtime and FFmpeg; drop the whole jniLibs directory in.
if [ -n "${FOTUFILM_ENGINE_JNILIBS:-}" ]; then
  cp -r "$FOTUFILM_ENGINE_JNILIBS/." "$STAGE/lib/$ABI/"
fi
rm -f "$OUT/FotufilmShell-unsigned.apk" "$OUT/FotufilmShell.apk"
( cd "$STAGE" && zip -q -r -X "../FotufilmShell-unsigned.apk" . )

echo "== 5/5 sign"
KS="$SHELL_DIR/debug.keystore"
if [ ! -f "$KS" ]; then
  "$JDK/bin/keytool" -genkeypair -keystore "$KS" -storepass android -keypass android \
    -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US" -noprompt >/dev/null 2>&1
fi
"$JDK/bin/java" -jar "$BT/lib/apksigner.jar" sign \
  --ks "$KS" --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias androiddebugkey --min-sdk-version $SIGN_MIN_API \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/FotufilmShell.apk" "$OUT/FotufilmShell-unsigned.apk"
"$JDK/bin/java" -jar "$BT/lib/apksigner.jar" verify --min-sdk-version $SIGN_MIN_API \
  "$OUT/FotufilmShell.apk"

echo
echo "built $OUT/FotufilmShell.apk"
ls -l "$OUT/FotufilmShell.apk" "$OUT/lib/$ABI/libfotufilm.so"
