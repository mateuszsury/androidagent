#!/usr/bin/env bash
# Emulator fixtures and real local adbd, no model login or physical OTG claim.
set -euo pipefail
cd "$(dirname "$0")/.."
bash tools/test-android.sh --build-only
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
"$ADB" install --no-incremental -r build/device-tests/tests.apk
SERIAL=$("$ADB" get-serialno)
ADBD_PORT=${ANDROIDAGENT_ADBD_PORT:-5555}
if [ -z "${ANDROIDAGENT_ADBD_PORT:-}" ] && [[ "$SERIAL" =~ ^emulator-([0-9]+)$ ]]; then
  ADBD_PORT=$((BASH_REMATCH[1] + 1))
fi
[[ "$ADBD_PORT" =~ ^[0-9]+$ ]] && (( ADBD_PORT > 0 && ADBD_PORT < 65536 )) || { echo 'Invalid test adbd port' >&2; exit 2; }
"$ADB" reverse tcp:15555 "tcp:$ADBD_PORT"
trap '"$ADB" reverse --remove tcp:15555 >/dev/null 2>&1 || true' EXIT
RUN_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
"$ADB" wait-for-device
"$ADB" shell rm -rf /sdcard/Download/androidagent-usb-tests
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
