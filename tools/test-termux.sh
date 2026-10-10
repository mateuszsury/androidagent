#!/usr/bin/env bash
# Real Termux integration. Use a disposable emulator with Termux, Codex, jq and curl installed.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
./tools/test-android.sh --build-only
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
"$ADB" install --no-incremental -r artifacts/androidagent-0.1.0.apk
"$ADB" install --no-incremental -r build/device-tests/tests.apk
export ANDROIDAGENT_TEST_RUN_ID="$(python3 -c 'import uuid; print(uuid.uuid4())')"
"$ADB" shell am instrument -w -e termux_only true -e run_id "$ANDROIDAGENT_TEST_RUN_ID" dev.mateuszsury.androidagent.tests/dev.mateuszsury.androidagent.AndroidAgentTests
mkdir -p artifacts/termux
"$ADB" pull /sdcard/Download/androidagent-runtime-tests/. artifacts/termux/
python3 - <<'PY'
import json,os
r=json.load(open('artifacts/termux/results.json'))
assert r.get('run_id') == os.environ['ANDROIDAGENT_TEST_RUN_ID'], 'Stale or missing runtime report'
print(f"{len(r['tests'])} Termux tests, {r['failures']} failures")
raise SystemExit(1 if r['failures'] else 0)
PY
