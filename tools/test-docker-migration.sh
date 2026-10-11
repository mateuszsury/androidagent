#!/usr/bin/env bash
# Isolated Android install-path checks; does not reset the actual user's VM.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL}"
bash tools/test-android.sh --build-only
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
"$ADB" install --no-incremental -r build/device-tests/tests.apk
RUN_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
API=$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')
"$ADB" shell rm -rf /sdcard/Download/androidagent-docker-migration-tests
"$ADB" shell am instrument -w -e run_id "$RUN_ID" dev.mateuszsury.androidagent.tests/dev.mateuszsury.androidagent.DockerMigrationTests
DEST="artifacts/api$API/docker-migration"
mkdir -p "$DEST"
"$ADB" pull /sdcard/Download/androidagent-docker-migration-tests/. "$DEST/"
python3 - "$RUN_ID" "$DEST" "$API" <<'PY'
import json,sys
report=json.load(open(sys.argv[2]+'/results.json'))
assert report['run_id']==sys.argv[1], 'Stale migration report'
assert report['api']==int(sys.argv[3]), 'Wrong API'
assert len(report['tests'])==4, 'Incomplete migration suite'
assert report['failures']==0 and all(test['pass'] for test in report['tests']), report
print('4 Android Docker migration checks passed (isolated sentinel disk, actual install path)')
PY
