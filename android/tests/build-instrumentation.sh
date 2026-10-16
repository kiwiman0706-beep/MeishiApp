#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
SDK_DIR="${ANDROID_SDK_ROOT:-/tmp/meishi-sdk}"
BT="$SDK_DIR/build-tools/35.0.0"
PLATFORM="$SDK_DIR/platforms/android-35/android.jar"
./gradlew :app:compileReleaseJavaWithJavac --no-daemon
APP_CLASSES=app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes
mkdir -p build/test/classes build/test/dex
"$BT/aapt2" link -o build/test/resources.apk -I "$PLATFORM" --manifest tests/android/AndroidManifest.xml --min-sdk-version 26 --target-sdk-version 35
"${JAVAC:-javac}" -source 8 -target 8 -encoding UTF-8 -bootclasspath "$PLATFORM:$BT/core-lambda-stubs.jar" -classpath "$APP_CLASSES" -d build/test/classes tests/android/Smoke.java
python3 - <<'PY'
import zipfile
from pathlib import Path
with zipfile.ZipFile('build/test/app-classes.jar','w') as z:
 for p in Path('app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes').rglob('*.class'):z.write(p,p.relative_to('app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes').as_posix())
with zipfile.ZipFile('build/test/classes.jar','w') as z:
 for p in Path('build/test/classes').rglob('*.class'):z.write(p,p.relative_to('build/test/classes').as_posix())
PY
"$BT/d8" --lib "$PLATFORM" --classpath build/test/app-classes.jar --min-api 26 --output build/test/dex build/test/classes.jar
cp build/test/resources.apk build/test/unsigned.apk
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('build/test/unsigned.apk','a') as z:z.write('build/test/dex/classes.dex','classes.dex')
PY
"$BT/zipalign" -f -p 4 build/test/unsigned.apk build/test/aligned.apk
"$BT/apksigner" sign --ks ../private-signing/meishinote-release.jks --ks-key-alias meishinote --ks-pass file:../private-signing/keystore-password.txt --out build/test/tests.apk build/test/aligned.apk
