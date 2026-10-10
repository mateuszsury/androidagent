#!/usr/bin/env bash
# Build a separate instrumentation APK with the same signing key as the app.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK}"
: "${KEYSTORE:?Set KEYSTORE to the development signing key}"
BT="$ANDROID_SDK_ROOT/build-tools/35.0.0"
SDK="$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
export PATH="$JAVA_HOME/bin:$BT:$PATH"
OUT=build/device-tests
mkdir -p "$OUT/classes" "$OUT/dex"
aapt2 link -I "$SDK" --manifest tests/android/AndroidManifest.xml -o "$OUT/base.apk"
javac --release 8 -classpath "$SDK:build/classes" -encoding UTF-8 -d "$OUT/classes" tests/android/AndroidAgentTests.java
d8 --min-api 30 --lib "$SDK" --classpath build/classes --output "$OUT/dex" $(find "$OUT/classes" -name '*.class')
python3 - "$OUT/base.apk" "$OUT/dex/classes.dex" <<'PY'
import sys,zipfile
with zipfile.ZipFile(sys.argv[1],'a',zipfile.ZIP_DEFLATED) as z:z.write(sys.argv[2],'classes.dex')
PY
zipalign -f 4 "$OUT/base.apk" "$OUT/aligned.apk"
apksigner sign --ks "$KEYSTORE" --ks-key-alias buddy --ks-pass "file:$(dirname "$KEYSTORE")/password" --out "$OUT/tests.apk" "$OUT/aligned.apk"
if [ "${1:-}" = --build-only ]; then
  echo "Built $OUT/tests.apk"
  exit 0
fi
"$ADB" install --no-incremental -r "$OUT/tests.apk"
export ANDROIDAGENT_TEST_RUN_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
"$ADB" shell am instrument -w -e run_id "$ANDROIDAGENT_TEST_RUN_ID" dev.mateuszsury.androidagent.tests/dev.mateuszsury.androidagent.AndroidAgentTests
mkdir -p artifacts/emulator
"$ADB" pull /sdcard/Download/androidagent-tests/. artifacts/emulator/
python3 - <<'PY'
import json,os
r=json.load(open('artifacts/emulator/results.json'))
assert r.get('run_id') == os.environ['ANDROIDAGENT_TEST_RUN_ID'], 'Stale or missing test report'
print(f"{len(r['tests'])} tests, {r['failures']} failures")
raise SystemExit(1 if r['failures'] else 0)
PY
