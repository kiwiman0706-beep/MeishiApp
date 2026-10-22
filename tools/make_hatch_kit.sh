#!/usr/bin/env bash
# Builds the Theta Hatch kit (specs, guides, lessons, a prompt and Meishi's code as a reference)
# for starting another Theta ○○ Hatch app. Usage: tools/make_hatch_kit.sh [output.zip]
# Never includes secrets (signing keys, passwords, API keys) or personal data.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION="$(sed -n "s/.*versionName '\(.*\)'.*/\1/p" android/app/build.gradle)"
OUT="${1:-theta-hatch-kit-meishi-$VERSION.zip}"
TMP="$(mktemp -d)"
K="$TMP/theta-hatch-kit"
mkdir -p "$K"/{spec,guides,reference/android/java,reference/android/res,reference/android/test,reference/ui,reference/desktop,reference/web,reference/workflows}

cp tools/hatch-kit/{README.md,PROMPT.md,LESSONS.md,REFERENCE.md} "$K/"
cp docs/THETA_HATCH_SPEC.md docs/THETA_VAULT_SPEC.md docs/THETA_SHARED_SETTINGS.md "$K/spec/"
cp docs/DRIVE_SETUP.md docs/WEB_SETUP.md docs/RELEASING.md "$K/guides/"

A=android/app/src/main
cp android/app/build.gradle android/build.sh "$A/AndroidManifest.xml" "$K/reference/android/"
cp "$A"/java/jp/yaegaki/meishinote/*.java "$K/reference/android/java/"
mkdir -p "$K/reference/android/res/layout" "$K/reference/android/res/xml" "$K/reference/android/res/drawable"
cp "$A"/res/layout/widget_*.xml "$K/reference/android/res/layout/"
cp "$A"/res/xml/*.xml "$K/reference/android/res/xml/"
cp "$A"/res/drawable/*.xml "$K/reference/android/res/drawable/"
cp android/app/src/test/java/jp/yaegaki/meishinote/{VaultCodecTest,VaultSyncTest,SyncTest,CrossPlatformTest,UpdateCheckTest,SeriesSettingsTest}.java "$K/reference/android/test/"
cp android/tests/ui.test.cjs "$K/reference/android/test/"
cp "$A"/assets/{index.html,app.js,style.css} "$K/reference/ui/"

mkdir -p "$K/reference/desktop/lib" "$K/reference/desktop/scripts" "$K/reference/desktop/assets" "$K/reference/desktop/test"
cp desktop/{package.json,main.js,README.md} "$K/reference/desktop/"
cp desktop/lib/*.js desktop/lib/prompts.json "$K/reference/desktop/lib/"
cp desktop/scripts/*.js "$K/reference/desktop/scripts/"
cp desktop/assets/{render.html,pdf-preload.js} "$K/reference/desktop/assets/"
cp desktop/test/*.js "$K/reference/desktop/test/"
cp -r desktop/test/e2e "$K/reference/desktop/test/"

mkdir -p "$K/reference/web/src" "$K/reference/web/test"
cp web/{package.json,build.js} "$K/reference/web/"
cp web/src/*.js "$K/reference/web/src/"
cp web/test/* "$K/reference/web/test/"
cp .github/workflows/*.yml "$K/reference/workflows/"

# Nothing secret or personal may slip in.
if grep -rIlE 'BEGIN (RSA |EC )?PRIVATE KEY|AIza[0-9A-Za-z_-]{30,}' "$K"; then echo "secret-like content found" >&2; exit 1; fi
find "$K" \( -name '*.jks' -o -name '*.keystore' -o -name '*password*' -o -name '*.apk' -o -name 'cards*.zip' \) | grep . && { echo "forbidden file" >&2; exit 1; }

(cd "$TMP" && zip -qr kit.zip theta-hatch-kit)
mv "$TMP/kit.zip" "$OUT"
rm -rf "$TMP"
echo "$OUT"
