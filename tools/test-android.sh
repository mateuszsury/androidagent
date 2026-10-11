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
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/assets"
aapt2 link -I "$SDK" --manifest tests/android/AdbFixtureManifest.xml -o "$OUT/fixture-unsigned.apk"
apksigner sign --ks "$KEYSTORE" --ks-key-alias buddy --ks-pass "file:$(dirname "$KEYSTORE")/password" --out "$OUT/assets/adb-fixture.apk" "$OUT/fixture-unsigned.apk"
aapt2 link -I "$SDK" --manifest tests/android/AndroidManifest.xml -A "$OUT/assets" -o "$OUT/base.apk"
javac --release 8 -classpath "$SDK:build/classes" -encoding UTF-8 -d "$OUT/classes" tests/android/*.java
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
FIXTURE_ARGS=()
if [[ "${ANDROIDAGENT_NOTIFICATION_FIXTURE:-false}" == true ]]; then
  # Explicit post-embedded test fixture: preserve other accessibility services and
  # all app data. Revoke before starting instrumentation, since revoke kills its UID.
  ENABLED_SERVICES=$("$ADB" shell settings get secure enabled_accessibility_services | tr -d '\r')
  SERVICES_VALUE=$(python3 - "$ENABLED_SERVICES" <<'PY'
import shlex,sys
services=[] if sys.argv[1] in ('', 'null') else sys.argv[1].split(':')
own={'dev.mateuszsury.androidagent/dev.mateuszsury.androidagent.BuddyService',
     'dev.mateuszsury.androidagent/.BuddyService'}
print(shlex.quote(':'.join(s for s in services if s not in own)))
PY
)
  "$ADB" shell "settings put secure enabled_accessibility_services $SERVICES_VALUE"
  "$ADB" shell am force-stop dev.mateuszsury.androidagent
  DEVICE_API=$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')
  if (( DEVICE_API >= 33 )); then
    "$ADB" shell pm revoke dev.mateuszsury.androidagent android.permission.POST_NOTIFICATIONS
    "$ADB" shell pm clear-permission-flags dev.mateuszsury.androidagent android.permission.POST_NOTIFICATIONS user-set user-fixed
  fi
  FIXTURE_ARGS=(-e notification_fixture true)
fi
# Clearing app data changes MediaStore ownership; otherwise it may rename new
# reports to results (1).json and leave the previous run at results.json.
"$ADB" shell rm -rf /sdcard/Download/androidagent-tests
"$ADB" shell am instrument -w -e run_id "$ANDROIDAGENT_TEST_RUN_ID" "${FIXTURE_ARGS[@]}" dev.mateuszsury.androidagent.tests/dev.mateuszsury.androidagent.AndroidAgentTests
DEST="${ANDROIDAGENT_ANDROID_EVIDENCE_DIR:-artifacts/emulator}"
mkdir -p "$DEST"
"$ADB" pull /sdcard/Download/androidagent-tests/. "$DEST/"
python3 - "$DEST" <<'PY'
import json,os,pathlib,sys
r=json.loads((pathlib.Path(sys.argv[1])/'results.json').read_text())
assert r.get('run_id') == os.environ['ANDROIDAGENT_TEST_RUN_ID'], 'Stale or missing test report'
print(f"{len(r['tests'])} tests, {r['failures']} failures")
raise SystemExit(1 if r['failures'] else 0)
PY
