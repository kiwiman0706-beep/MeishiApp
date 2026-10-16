#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_DIR" ]]; then echo 'Set ANDROID_SDK_ROOT to your Android SDK directory.' >&2; exit 1; fi
BUILD_TOOLS="$SDK_DIR/build-tools/35.0.0"
SIGNING_FILE="${SIGNING_FILE:-../private-signing/meishinote-release.jks}"
SIGNING_PASS_FILE="${SIGNING_PASS_FILE:-../private-signing/keystore-password.txt}"
export ANDROID_SDK_ROOT="$SDK_DIR"
./gradlew :app:assembleRelease --no-daemon
mkdir -p build
"$BUILD_TOOLS/zipalign" -f -P 16 4 app/build/outputs/apk/release/app-release-unsigned.apk build/aligned.apk
"$BUILD_TOOLS/apksigner" sign --ks "$SIGNING_FILE" --ks-key-alias meishinote --ks-pass "file:$SIGNING_PASS_FILE" --out build/meishinote-0.2.0.apk build/aligned.apk
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs build/meishinote-0.2.0.apk
