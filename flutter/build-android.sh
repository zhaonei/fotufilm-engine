#!/bin/bash
# 接收外部传入的宿主机工具链路径
if [ -n "${SWIFT_TOOLCHAIN:-}" ]; then
    export PATH="$SWIFT_TOOLCHAIN/bin:$PATH"
    echo "✅ 已接收宿主机工具链: $SWIFT_TOOLCHAIN"
    echo "当前 swift 指向: $(which swift)"
    swift --version
else
    echo "⚠️ 未提供 SWIFT_TOOLCHAIN 环境变量"
    exit 1
fi

set -euo pipefail
cd "$(cd "$(dirname "$0")" && pwd -P)/.."
flutter_tool="${FLUTTER:-flutter}"
artifact=apk
[[ " $* " == *" --appbundle "* ]] && artifact=appbundle

[[ -f "${FOTUFILM_ANDROID_DEPS:-build/android-deps}/arm64/lib/libjpeg.a" ]] || tools/build-android-codecs.sh
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
  output=build/flutter-host/Fotufilm.apk
  cp "$stage/flutter/build/app/outputs/flutter-apk/app-release.apk" "$output"
else
  output=build/flutter-host/Fotufilm.aab
  cp "$stage/flutter/build/app/outputs/bundle/release/app-release.aab" "$output"
fi
echo "Built $output ($version build $build)"
