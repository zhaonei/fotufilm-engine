# android-shell

The smallest Android app that loads the engine through JNI: a `libfotufilm.so` for arm64, a Java
activity that calls it, and a build that needs neither Gradle nor aapt2 nor an x86-64 compiler.

```
android-shell/
├── CMakeLists.txt                 libfotufilm.so (CMake drives either toolchain below)
├── build.sh                       → out/FotufilmShell.apk
├── cmake/host-aarch64-android28.cmake   toolchain for hosts the NDK ships no compiler for
├── toolchain/aarch64-linux-android28-clang{,++}
├── jni/fotufilm_jni.cpp           the JNI bridge: only ever calls fotufilm.h
├── jni/fotufilm_engine_stub.cpp   placeholder engine, same ABI (see below)
├── app/                           AndroidManifest.xml + the activity
└── tools/mkaxml.py                binary XML manifest writer
```

## What it talks to

`Sources/CFotufilmHost/include/fotufilm.h` — the engine's one public C interface. The bridge calls
nothing else, so the shell keeps working when the library behind it changes:

| Java (`com.fotufilm.shell.Shell`) | C API |
| --- | --- |
| `nativeApiVersion()` | `fotufilm_api_version()` |
| `nativeCapabilities()` | `fotufilm_capabilities()` |
| `nativeCreateEngine()` | `fotufilm_engine_create()` + `fotufilm_engine_describe()` |
| `nativeRenderProbe()` | `fotufilm_engine_create()` → `fotufilm_render()` → `fotufilm_free()` |

## Building

Needs: clang + lld, cmake + ninja (or `FOTUFILM_DIRECT_CLANG=1`), JDK 17, python3, zip, an
unpacked NDK (r26+), and the SDK's `build-tools` (`d8.jar`, `apksigner.jar`) plus a platform
`android.jar`.

```sh
export ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.2.12479018
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk
export BUILD_TOOLS=~/Android/Sdk/build-tools/35.0.0
export ANDROID_JAR=~/Android/Sdk/platforms/android-35/android.jar
./android-shell/build.sh
```

`FOTUFILM_PLACEHOLDER_ENGINE` chooses what is inside the library:

* **on** (default) — `jni/fotufilm_engine_stub.cpp`, which implements every symbol of the real ABI
  and answers `FOTUFILM_ERROR` with an explaining string. The shell, the loader path, the JNI
  names and the packaging are all exercised; only the pixels are missing.
* **off** — the library carries the engine built by the real pipeline.

### The genuine engine

`tools/build-android-engine.sh` produces `flutter/build/android-engine/jniLibs/arm64-v8a/` — that
library plus the Swift runtime and FFmpeg beside it. Point the shell at it and rebuild:

```sh
FOTUFILM_REAL_ENGINE=flutter/build/android-engine/jniLibs/arm64-v8a/libfotufilm.so \
FOTUFILM_ENGINE_JNILIBS=flutter/build/android-engine/jniLibs/arm64-v8a \
  ./android-shell/build.sh
```

No Java or JNI change is involved: same symbols, same semantics.

## Notes on hosts

The NDK ships compilers for x86-64 and Apple hosts only, so `build.sh` picks its toolchain:

* **x86-64 / macOS** — the NDK's own `build/cmake/android.toolchain.cmake`.
* **aarch64 Linux** (where none of the NDK's compilers run) — `cmake/host-aarch64-android28.cmake`,
  which drives the host's clang against the NDK's *target* pieces: bionic sysroot, libc++ for
  android, compiler-rt builtins, libunwind. All of those are aarch64 code, so the host's
  architecture does not matter. `toolchain/` also handles two host quirks: musl's
  `-lssp_nonshared` (an empty archive answers it) and clang++'s default `-lc++`, which would
  otherwise resolve to a `libc++_shared.so` no device ships.

The manifest is written as binary XML by `tools/mkaxml.py`: aapt2 is an x86-64 binary, and the
manifest declares no resources, so no `resources.arsc` is needed. `d8` and `apksigner` are pure
Java and run anywhere a JDK does. The APK is signed with a throwaway `debug.keystore`
(`storepass`/`keypass` = `android`) that `build.sh` creates — replace it for anything you ship.

See `BUILD-REPORT.md` for what was measured while getting this to build, including the error
messages the official pipeline gives on an aarch64/musl host.
