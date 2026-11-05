# android-shell

**Not the shipped app.** The released APK is the Flutter editor, built by `flutter/build-android.sh`
and published by `.github/workflows/build-apk.yml`; this directory is kept as (a) a minimal
harness that loads `libfotufilm.so` through JNI on a phone in seconds, useful when the question is
"does the engine load at all", and (b) the home of the signing keystore the release pipeline uses.

The smallest Android app that loads the engine through JNI: a standard Gradle project whose
`arm64-v8a` libraries are `libfotufilm.so` plus whatever rides beside it.

```
android-shell/
├── settings.gradle.kts, build.gradle.kts, gradle.properties    AGP 8.7.3 / Gradle 8.9
├── app/build.gradle.kts                                        namespace, sdk levels, jniLibs
├── app/src/main/
│   ├── AndroidManifest.xml                                     aapt2 turns this into the binary one
│   ├── java/com/fotufilm/shell/{MainActivity,Shell}.java        the activity and the native calls
│   └── jniLibs/arm64-v8a/                                      written by ./build.sh, not committed
├── build.sh                    builds or copies the native libraries into jniLibs
├── CMakeLists.txt              libfotufilm.so from jni/ (placeholder engine, real ABI)
├── cmake/host-aarch64-android28.cmake   toolchain for hosts the NDK ships no compiler for
├── toolchain/aarch64-linux-android28-clang{,++}
├── jni/fotufilm_jni.cpp        the JNI bridge: only ever calls fotufilm.h
└── jni/fotufilm_engine_stub.cpp   placeholder engine, same ABI (see below)
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

Two steps: the native libraries, then Gradle.

```sh
export ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.2.12479018
./android-shell/build.sh                 # → app/src/main/jniLibs/arm64-v8a/libfotufilm.so

cd android-shell
./gradlew assembleRelease                # → app/build/outputs/apk/release/app-release.apk
```

`gradle-wrapper.jar` is a binary and is not in the tree; run `gradle wrapper --gradle-version 8.9`
once to generate the wrapper (the workflow does exactly that), or use `gradle assembleDebug`
directly.

### Signing

`keystore/fotufilm-shell.jks` is committed, password and all: `fotufilm`. That is deliberate.
AGP's own debug keystore lives in `~/.android` and is generated per machine, so every CI run
would produce a different certificate and no build could install over the previous one. This key
is a shell identity, not a secret — never put anything you care about behind it.

`app/build.gradle.kts` turns v1 signing on explicitly. AGP defaults it to `minSdk < 24`, and this
app is minSdk 28, so a VIVO installer reads the certificate out of `META-INF` (the v1 / JAR
signature), finds none — v2/v3 had signed the APK perfectly well — and reports
"安装包缺乏开发者证书". The CI step therefore fails unless `apksigner verify --min-sdk-version 18`
reports v1, v2 and v3 all true.

`FOTUFILM_PLACEHOLDER_ENGINE` in `CMakeLists.txt` chooses what is inside the library:

* **on** (default) — `jni/fotufilm_engine_stub.cpp`, which implements every symbol of the real ABI
  and answers `FOTUFILM_ERROR` with an explaining string. The shell, the loader path, the JNI
  names and the packaging are all exercised; only the pixels are missing.
* **off** — the library carries the engine built by the real pipeline.

### The genuine engine

`tools/build-android-engine.sh` produces `flutter/build/android-engine/jniLibs/arm64-v8a/` — that
library plus the Swift runtime and FFmpeg beside it. `build.sh` copies them in, shebang-style:

```sh
FOTUFILM_REAL_ENGINE=flutter/build/android-engine/jniLibs/arm64-v8a/libfotufilm.so \
FOTUFILM_ENGINE_JNILIBS=flutter/build/android-engine/jniLibs/arm64-v8a \
  ./android-shell/build.sh
```

No Java or JNI change is involved: same symbols, same semantics.

## Why Gradle rather than d8 + apksigner by hand

The manifest, the resources table (`resources.arsc`), the dex, the zip alignment and the signature
all come from aapt2/d8/apksigner driven by AGP, which is the combination every installer is tested
against. An earlier version of this directory assembled those by hand — including a Python writer
for the binary manifest, because the SDK's aapt2 is an x86-64 binary — and an OEM installer
refused the result with "解析软件包时出现问题". Gradle removes that variable.

`packaging { jniLibs { useLegacyPackaging = true } }` sets `extractNativeLibs="true"`: the
libraries are extracted at install time instead of being mapped out of the APK, which needs no
page alignment and is the safer of the two for an app carrying a Swift runtime and FFmpeg.

## Notes on hosts

The NDK ships compilers for x86-64 and Apple hosts only, so `build.sh` picks its toolchain:

* **x86-64 / macOS** — the NDK's own `build/cmake/android.toolchain.cmake`.
* **aarch64 Linux** (where none of the NDK's compilers run) — `cmake/host-aarch64-android28.cmake`,
  which drives the host's clang against the NDK's *target* pieces: bionic sysroot, libc++ for
  android, compiler-rt builtins, libunwind. All of those are aarch64 code, so the host's
  architecture does not matter. `toolchain/` also handles two host quirks: musl's
  `-lssp_nonshared` (an empty archive answers it) and clang++'s default `-lc++`, which would
  otherwise resolve to a `libc++_shared.so` no device ships.

Gradle itself needs an x86-64 host (AGP's aapt2 and its other tools are x86-64 binaries), so the
APK is built in CI or on a desktop, not on the phone.

See `BUILD-REPORT.md` for what was measured while getting this to build, including the error
messages the official pipeline gives on an aarch64/musl host.
