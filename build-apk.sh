#!/usr/bin/env bash
set -euo pipefail

export JAVA_HOME="$HOME/.local/share/noctalia-android-toolchain/jdk"
export ANDROID_HOME="$HOME/.local/share/noctalia-android-toolchain/sdk"
export PATH="$JAVA_HOME/bin:$HOME/.local/share/noctalia-android-toolchain/gradle/gradle-8.11.1/bin:$ANDROID_HOME/platform-tools:$PATH"

cd "$(dirname "$0")"

EXTRA_ARGS=()
OUT_NAME="AntigravityRemote-arm64.apk"

for arg in "$@"; do
    case "$arg" in
        --universal)
            EXTRA_ARGS+=("-PgeckoOmni=true")
            OUT_NAME="AntigravityRemote-universal.apk"
            ;;
    esac
done

gradle assembleRelease --no-daemon "${EXTRA_ARGS[@]}"

APK="app/build/outputs/apk/release/app-release.apk"
cp -f "$APK" "./$OUT_NAME"
echo "APK listo en: $(pwd)/$OUT_NAME ($(du -h "./$OUT_NAME" | cut -f1))"

for arg in "$@"; do
    if [[ "$arg" == "--install" ]]; then
        adb install -r "./$OUT_NAME"
    fi
done
