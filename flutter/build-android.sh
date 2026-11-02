
#!/bin/bash
# 🌟 强行注入引擎脚本所需的所有绝对路径
export SWIFT_TOOLCHAIN="$HOME/.local/share/swiftly/toolchains/6.3.3/usr"
export SWIFT_SDK="swift-6.3.3-RELEASE_android"
export ANDROID_NDK_HOME="$GITHUB_WORKSPACE/android-ndk-r26d"
export ANDROID_HOME="$GITHUB_WORKSPACE/android-sdk" # 随便给个存在的路径防止找不到
export PATH="$SWIFT_TOOLCHAIN/bin:$PATH"

# 以下是你原本的代码，不要动
set -euo pipefail
# ... 保持原样 ...
# 🌟 暴力注入：强行把宿主机 Swift 路径写死，防止子脚本丢失
export SWIFT_TOOLCHAIN="$HOME/.local/share/swiftly/toolchains/6.3.3/usr"
export PATH="$SWIFT_TOOLCHAIN/bin:$PATH"
set -euo pipefail
cd "$(cd "$(dirname "$0")" && pwd -P)/.."
flutter_tool="${FLUTTER:-flutter}"
artifact=apk
[[ " $* " == *" --appbundle "* ]] && artifact=appbundle

[[ -f "${FOTUFILM_ANDROID_DEPS:-build/android-deps}/arm64/lib/libjpeg.a" ]] || tools/build-android-codecs.sh

# 注意：如果你的 tools/build-android-engine.sh 里有检测 SWIFT_TOOLCHAIN 的代码，请去那个文件里把它删掉。
tools/build-android-engine.sh

export FOTUFILM_ANDROID_ENGINE="$PWD/flutter/build/android-engine"
tool_dir="$PWD/flutter"
(cd flutter && tool/prepare.sh)
stage="$(mktemp -d /tmp/fotufilm.XXXXXX)"
trap 'rm -rf "$stage"' EXIT
rsync -a --exclude /build --exclude /.dart_tool --exclude /android/.gradle \
  --exclude /android/local.properties --exclude /android/app/.cxx "$tool_dir/" "$stage/flutter/"

version="$(sed -n 's/^MARKETING_VERSION=//p' version.env)"
build="$(sed -n 's/^CURRENT_PROJECT_VERSION=//p' version.env)"
(
  cd "$stage/flutter"
  "$flutter_tool" --suppress-analytics build "$artifact" --release --target-platform android-arm64 \
    --build-name "$version" --build-number "$build"
)
mkdir -p build/flutter-host
if [[ "$artifact" == apk ]]; then
  cp "$stage/flutter/build/app/outputs/flutter-apk/app-release.apk" "build/flutter-host/Fotufilm.apk"
fi
