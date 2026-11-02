#!/bin/bash
# Builds Fotufilm for Android: the engine library (tools/build-android-engine.sh) and the Flutter
# editor around it.
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
if [[ "${FOTUFILM_SOURCE_BUILD:-1}" == 0 && "${FOTUFILM_USE_SOURCE_IDENTITY:-0}" != 1 ]]; then
  export FOTUFILM_ANDROID_APPLICATION_ID=com.muastudio.fotufilm
fi
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
echo "Built $output"
