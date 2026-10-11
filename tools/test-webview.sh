#!/usr/bin/env bash
# Tests the production preview independently of the slow nested Docker VM.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the emulator or device under test}"
bash tools/test-android.sh --build-only
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
"$ADB" install --no-incremental -r build/device-tests/tests.apk
RUN_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
API=$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')
"$ADB" shell rm -rf /sdcard/Download/androidagent-webview-tests
"$ADB" shell am instrument -w -e run_id "$RUN_ID" dev.mateuszsury.androidagent.tests/dev.mateuszsury.androidagent.WebViewRenderingTests
DEST="artifacts/api$API/webview"
mkdir -p "$DEST"
"$ADB" pull /sdcard/Download/androidagent-webview-tests/. "$DEST/"
python3 - "$RUN_ID" "$DEST" "$API" <<'PY'
import json,sys,pathlib
report=json.load(open(sys.argv[2]+'/results.json'))
assert report['run_id']==sys.argv[1], 'Stale WebView report'
assert report['api']==int(sys.argv[3]), 'Wrong device API'
assert len(report['tests'])==8, 'Incomplete WebView suite'
assert report['failures']==0 and all(t['pass'] for t in report['tests']), report
assert report['native_toolbar_frame_verified'], 'Native toolbar was not verified in the actual frame'
assert (pathlib.Path(sys.argv[2])/'preview.png').stat().st_size>0, 'Missing preview frame'
print('8 production WebView checks passed; provider:',report['provider'])
PY
