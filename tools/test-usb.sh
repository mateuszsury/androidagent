#!/usr/bin/env bash
# Emulator fixtures and real local adbd, no model login or physical OTG claim.
set -euo pipefail
cd "$(dirname "$0")/.."
bash tools/test-android.sh --build-only
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
"$ADB" install --no-incremental -r build/device-tests/tests.apk
"$ADB" reverse tcp:15555 tcp:5555
RUN_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
"$ADB" wait-for-device
"$ADB" shell am instrument -w -e run_id "$RUN_ID" dev.mateuszsury.androidagent.tests/dev.mateuszsury.androidagent.UsbRuntimeTests
mkdir -p artifacts/usb
"$ADB" pull /sdcard/Download/androidagent-usb-tests/. artifacts/usb/
python3 - "$RUN_ID" <<'PY'
import json,sys
r=json.load(open('artifacts/usb/results.json'))
assert r['run_id']==sys.argv[1], 'Stale USB evidence'
print(f"{len(r['tests'])} USB checks, {r['failures']} failures")
raise SystemExit(bool(r['failures']))
PY
