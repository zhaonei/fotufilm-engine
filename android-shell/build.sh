#!/bin/sh
# Puts the native libraries where Gradle packages them from:
#
#   app/src/main/jniLibs/arm64-v8a/
#
#   ANDROID_NDK_HOME=… ./android-shell/build.sh
#       builds jni/fotufilm_*.cpp into libfotufilm.so (the placeholder engine, real ABI)
#
#   FOTUFILM_REAL_ENGINE=/path/to/libfotufilm.so \
#   FOTUFILM_ENGINE_JNILIBS=/path/to/jniLibs/arm64-v8a ./android-shell/build.sh
#       copies the engine and the runtime beside it instead -- same ABI, no Java change
#
# The APK itself is Gradle's: cd android-shell && ./gradlew assembleDebug
set -eu
cd "$(dirname "$0")/.."        # the repository root: Sources/CFotufilmHost lives there
ROOT="$PWD"
SHELL_DIR="$ROOT/android-shell"
NDK="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to an unpacked Android NDK}"
ABI=arm64-v8a
DEST="$SHELL_DIR/app/src/main/jniLibs/$ABI"

mkdir -p "$DEST"

if [ -n "${FOTUFILM_REAL_ENGINE:-}" ]; then
  cp "$FOTUFILM_REAL_ENGINE" "$DEST/libfotufilm.so"
  # The engine opens FFmpeg by name and links the Swift runtime; both ride in jniLibs.
  if [ -n "${FOTUFILM_ENGINE_JNILIBS:-}" ]; then
    cp -a "$FOTUFILM_ENGINE_JNILIBS/." "$DEST/"
  fi
  echo "engine libraries from $FOTUFILM_REAL_ENGINE"
  exit 0
fi

JDK="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk}"
PREBUILT="$(ls -d "$NDK"/toolchains/llvm/prebuilt/*/ | head -1)"
SYSROOT="${PREBUILT%/}/sysroot"
LIBCXX="$SYSROOT/usr/lib/aarch64-linux-android"

if [ -z "${FOTUFILM_DIRECT_CLANG:-}" ] && command -v cmake >/dev/null 2>&1; then
  TOOLCHAIN="$SHELL_DIR/cmake/host-aarch64-android28.cmake"
  if [ -f "$NDK/build/cmake/android.toolchain.cmake" ] && [ "$(uname -m)" != aarch64 ]; then
    TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"
  fi
  cmake -S "$SHELL_DIR" -B "$SHELL_DIR/build" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DANDROID_ABI=$ABI -DANDROID_PLATFORM=android-28 -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=Release > /dev/null
  cmake --build "$SHELL_DIR/build" --target fotufilm
  cp "$SHELL_DIR/build/libfotufilm.so" "$DEST/libfotufilm.so"
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
    -o "$DEST/libfotufilm.so"
fi

find "$DEST" -maxdepth 1 -type f -name '*.so' -exec ls -l {} +
