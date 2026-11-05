# Fotufilm 引擎 Android 交叉编译 —— 构建报告

日期：2026-11-05 ｜ 宿主：Alpine Linux 3.21.8 / aarch64 / musl ｜ 仓库：zhaonei/fotufilm-engine @ main（版本 1.14，build 1024）

---

## 一、结论先说

| 目标 | 结果 |
| --- | --- |
| 1. 克隆仓库 | ✅ 完成（`git clone` 被网络重置，改用 codeload tarball） |
| 2. 定位 Android 交叉编译脚本 | ✅ `tools/build-android-engine.sh`（+ `build-android-codecs.sh`、`generate-halide-aot-android.sh`） |
| 3. 交叉编译出 arm64 `libfotufilm.so` | ⚠️ **产出了** `android-shell/out/lib/arm64-v8a/libfotufilm.so`（真实 arm64 ELF、JNI 导出、只依赖 libc/libdl），但**里面是占位引擎，不是 Swift+Halide 真引擎** |
| 4. JNI 壳 + 打包 APK | ✅ `android-shell/out/FotufilmShell.apk`（已签名、清单经独立解析器验证正确） |

**为什么真引擎没编出来**：不是脚本写错，而是这台机器缺两样东西，且都不是 `apk add` 能补的：

1. **Android NDK 没有 aarch64 Linux 宿主版编译器。** NDK r27c 只发行 `linux-x86_64` / `darwin-*` / `windows-x86_64` 预编译工具链。本机是 aarch64，
   NDK 自带的 `clang-18` 是 x86-64 ELF，执行直接失败（证据见附录 A）。
2. **Swift 引擎需要 swift.org 工具链 + Swift SDK for Android + 打了 Vulkan 补丁的 Halide。**
   官方脚本第一行硬检查 `SWIFT_TOOLCHAIN`（证据见附录 B）：
   - swift.org 的 Linux 工具链是 glibc 构建，本机是 musl，需要 gcompat 转接，风险高；
   - `third_party/Halide` 是 git submodule（tarball 里是空目录），且 Android 内核要求**打过 Vulkan 补丁**的 Halide，需要先有 LLVM 才能编译 Halide；
   - `tools/build-android-codecs.sh` 里 `HOST_TAG` 只认 `linux-x86_64` / `darwin-*`，同样假设 x86-64 宿主。

  按现有下载速度估算，补齐这条链需要下载约 3–4 GB（LLVM ~1.5 GB、Swift 工具链 ~800 MB、Swift Android SDK ~400 MB、8 个编解码库源码 + FFmpeg）并编译数小时，且 Swift/musl 那一步成功率不高。所以我**没有**假装编出真引擎。

---

## 二、这个项目长什么样（结构分析）

```
fotufilm-engine/
├── Package.swift              Swift Package，多 target
├── Sources/
│   ├── FotufilmCore/          可移植引擎核心（Swift，含 AndroidMath.swift）
│   ├── FotufilmHalide/        Halide 管线；FotufilmHalideLinux.cpp 是 Android 走的 shim
│   ├── CFotufilmHost/         C API：include/fotufilm.h（169 行，唯一对外 ABI）
│   ├── CFotufilmCodecs/       照片编解码（LibRaw/HEIF/TIFF/PNG/JPEG/EXR）
│   ├── CFotufilmVideo/        FFmpeg 视频，运行时 dlopen
│   ├── FotufilmHost/…         Swift 侧实现（fotufilm.c 只是 header 载体，2 行）
├── tools/
│   ├── build-android-engine.sh       ← 真正的 Android 目标产物脚本
│   ├── build-android-codecs.sh       ← 8 个编解码库 + FFmpeg 交叉编译
│   ├── generate-halide-aot-android.sh← 用 Halide 生成 Vulkan AOT 内核
│   ├── verify-android-aot.sh         ← 宿主上跑 AOT/CPU 一致性（不依赖真机）
│   └── android-aot-parity.cpp 等
├── flutter/build-android.sh           Flutter 壳（gradle 出 APK，需要 flutter + x86-64 SDK）
└── third_party/Halide                 git submodule（tarball 中为空）
```

产物形态：`flutter/build/android-engine/jniLibs/arm64-v8a/libfotufilm.so` = Swift 引擎
（`swiftrt.o` + `FotufilmHost.o` + Halide shim + codecs 静态库 + Halide AOT 内核 `.a`），
链接由 NDK 的 `aarch64-linux-android28-clang++` 完成，目标 triple `aarch64-unknown-linux-android28`。

---

## 三、我在本机做了什么（可复现）

### 1) 装环境（全部 `apk add`，镜像走华为云，很快）

```sh
apk add --no-cache build-base clang lld cmake ninja python3 openjdk17-jdk \
        zip unzip file binutils automake autoconf libtool pkgconf patchelf
```

外加从腾讯云镜像下载（十几 MB/s，NDK 633 MB 一分钟内）：

```sh
curl -L -o android-ndk-r27c-linux.zip https://mirrors.cloud.tencent.com/AndroidSDK/android-ndk-r27c-linux.zip
curl -L -o build-tools_r35_linux.zip   https://mirrors.cloud.tencent.com/AndroidSDK/build-tools_r35_linux.zip
curl -L -o platform-35_r02.zip         https://mirrors.cloud.tencent.com/AndroidSDK/platform-35_r02.zip
```

### 2) 修掉「NDK 宿主编译器跑不了」这一环（关键 workaround）

NDK 的**目标端**件是宿主无关的：bionic sysroot、libc++ for android、compiler-rt builtins、
libunwind 都是 aarch64 目标代码。所以用**本机原生 clang 当驱动**、把 `--target` 指向 Android 即可：

```sh
clang --target=aarch64-linux-android28 \
      --sysroot=$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot \
      -resource-dir=$NDK/toolchains/llvm/prebuilt/linux-x86_64/lib/clang/18 \
      -L$SYSROOT/usr/lib/aarch64-linux-android/28 -L$SYSROOT/usr/lib/aarch64-linux-android \
      -Ltoolchain/shim        # 里面是一个空 libssp_nonshared.a
```

两个坑及修法（已固化在 `toolchain/aarch64-linux-android28-clang{,++}`）：

- Alpine 的 clang 默认加 `-lssp_nonshared`（musl 加固库），Android 没有 → 塞一个空归档。
- Alpine 的 `clang++` 默认 `-stdlib=libc++` 并加 `-lc++`，会解析成 `libc++_shared.so`（设备上没有）
  → 用 `-nostdlib++` + 显式静态链接 `libc++_static.a libc++abi.a`，并 `-Wl,--exclude-libs,ALL`。
- 不要给 NDK 的 `-B .../prebuilt/linux-x86_64/bin`（那会把链接器指到 x86-64 的 `ld.lld`）。

验证：`readelf -d libfotufilm.so` 只剩 `libc.so` / `libdl.so`，`Machine: AArch64`，`for Android 28`。

### 3) JNI 壳与 APK —— 本机没有 x86-64 的 aapt2，所以自己写清单编码器

- `jni/fotufilm_engine_stub.cpp`：按 **真实的 `fotufilm.h` ABI** 实现全部导出符号（占位语义，见文件头注释）。
- `jni/fotufilm_jni.cpp`：`Java_com_fotufilm_shell_Shell_*` 四个 JNI 入口，只走公开 C API。
- `app/src/…`：Java 壳，`System.loadLibrary("fotufilm")` → 调 API → 结果贴到界面 + logcat。
- `tools/mkaxml.py`：**手写二进制 XML（AXML）**（string pool / resource map / 元素树），
  因为 aapt2 只有 x86-64 版；清单不引用任何资源，所以不需要 `resources.arsc`。
- dex 与签名：`build-tools` 里的 `d8.jar`、`apksigner.jar` 是纯 Java，在 arm64 的 JDK 17 上正常跑。

一键：`./android-shell/build.sh` → `android-shell/out/FotufilmShell.apk`（环境变量见 `android-shell/README.md`）。
`.so` 的编译走 `android-shell/CMakeLists.txt`：x86-64/macOS 宿主用 NDK 自带的
`android.toolchain.cmake`，aarch64 Linux 宿主用 `cmake/host-aarch64-android28.cmake` 驱动本机 clang；
`FOTUFILM_DIRECT_CLANG=1` 则跳过 CMake 直接调 `toolchain/` 里的包装器（三条路径都实测过）。

### 4) 验证做到了哪一步

- `apksigner verify` 通过（v1 + v2 scheme）。
- 用**独立实现** pyaxmlparser 解析 APK（不是我自己的编码器，避免自证）：
  ```
  package   : com.fotufilm.shell
  app name  : Fotufilm Shell
  version   : 1.0 1
  min/target: 28 34
  main      : com.fotufilm.shell.MainActivity
  files     : lib/arm64-v8a/libfotufilm.so, AndroidManifest.xml, classes.dex, META-INF/…
  ```
- **还没做的**：真机安装 + 运行（`pm` 在容器里跑不了 —— 缺 `/system/bin/linker64`；
  Shizuku 未授权）。授权后可以直接：
  ```sh
  pm install -r -t out/FotufilmShell.apk && am start -n com.fotufilm.shell/.MainActivity
  logcat -s FotufilmShell
  ```

---

## 四、要把**真引擎**编出来，两条路

### 路线 A（推荐，省事）：换台 x86-64 的 Linux 或 macOS

NDK + 官方脚本都是按这个假设写的，基本照 README 跑：

```sh
export ANDROID_NDK_HOME=~/Android/Sdk/ndk/27.x
export SWIFT_TOOLCHAIN=~/Library/Developer/Toolchains/swift-6.3.3-RELEASE.xctoolchain   # swift.org
swift sdk install https://download.swift.org/swift-6.3.3-release/android/swift-6.3.3-RELEASE_android.artifactbundle.tar.gz
git submodule update --init third_party/Halide
tools/build-halide-linux.sh          # 或 build-halide.sh（含 Vulkan 补丁）
HALIDE_ROOT=build/halide-install tools/generate-halide-aot-android.sh
export HALIDE_ROOT=build/halide-install
tools/build-android-codecs.sh
tools/build-android-engine.sh        # → flutter/build/android-engine/jniLibs/arm64-v8a/libfotufilm.so
```

拿到真 `libfotufilm.so` 后，一条命令重打包（Java/JNI 侧一行都不用改，JNI 只调 `fotufilm.h` 的公开符号）：

```sh
FOTUFILM_REAL_ENGINE=/path/to/flutter/build/android-engine/jniLibs/arm64-v8a/libfotufilm.so ./build.sh
```

注意：真引擎的 `.so` 还带着 Swift 运行时（`libswiftCore.so` 等）和 FFmpeg，`tools/build-android-engine.sh`
会把它们一起放进 `jniLibs/arm64-v8a/`。那样需要把它们一并拷进 APK 的 `lib/arm64-v8a/`，
`build.sh` 里第 4 步只要多 `cp` 一次整个 `jniLibs` 目录即可。

### 路线 B：就在这台 aarch64 机器上硬啃（我没做，风险清单）

1. patch `build-android-codecs.sh` 的 `HOST_TAG`，改成用 `toolchain/aarch64-linux-android28-clang*` 包装器；
   8 个库（jpeg/png/tiff/lcms/libraw/de265/heif/imath/openexr）+ FFmpeg 逐个交叉编译。
2. `apk add llvm19-dev` 后用它对 Halide 打 Vulkan 补丁并编译（Halide 跟 main 走，pin 在 submodule 里，
   tarball 拿不到 pin，需要能 `git clone` 下来的网络）。
3. swift.org 的 aarch64 Linux（glibc）工具链 + `apk add gcompat`，再装 Swift SDK for Android，
   让 `swiftc -target aarch64-unknown-linux-android28` 跑通 —— 这一步在 musl 上最容易翻车。
4. 总成本：约 3–4 GB 下载 + 数小时编译，且 3 的成功率不高。

---

## 五、目录（本仓库 `android-shell/`）

```
android-shell/
├── CMakeLists.txt                libfotufilm.so（含占位引擎开关 FOTUFILM_PLACEHOLDER_ENGINE）
├── build.sh                      一键构建（.so → dex → AXML → zip → 签名 → verify）
├── cmake/host-aarch64-android28.cmake  aarch64 宿主的 CMake toolchain
├── toolchain/aarch64-linux-android28-clang{,++}   NDK-sysroot 交叉驱动（含坑位修复）
├── jni/fotufilm_engine_stub.cpp  按 fotufilm.h ABI 的占位引擎（含真错误串）
├── jni/fotufilm_jni.cpp          JNI 桥
├── app/AndroidManifest.xml + app/src/…/Shell.java, MainActivity.java
├── tools/mkaxml.py               二进制清单编码器（替代 x86-64 的 aapt2）
└── out/                          构建产物（.gitignore 忽略）
```

## 附录 A：NDK 宿主编译器无法执行

```
$ file ~/dl/android-ndk-r27c/toolchains/llvm/prebuilt/*/bin/clang-18
clang-18: ELF 64-bit LSB pie executable, x86-64 … interpreter /lib64/ld-linux-x86-64.so.2
$ ./clang-18 --version
cannot execute: required file not found
$ ls ~/dl/android-ndk-r27c/toolchains/llvm/prebuilt/
linux-x86_64          # 只有这一个
```

## 附录 B：官方脚本自己报的错

```
$ ANDROID_NDK_HOME=~/dl/android-ndk-r27c bash tools/build-android-engine.sh
error: set SWIFT_TOOLCHAIN to a swift.org toolchain matching the Swift SDK
       (Xcode's Swift cannot read the SDK's modules)

$ bash tools/build-android-codecs.sh --no-video
== downloading libjpeg-turbo-3.2.0.tar.gz
curl: (35) TLS connect error: error:0A000126:SSL routines::unexpected eof while reading
  （`HOST_TAG=linux-x86_64` 写死；即便下载成功，下一步调用的也是 x86-64 的 clang）
```
