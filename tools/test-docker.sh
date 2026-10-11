#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
bash tools/test-android.sh --build-only
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
"$ADB" install --no-incremental -r build/device-tests/tests.apk
RUN_ID=$(python3 -c 'import uuid; print(uuid.uuid4())')
NAME=docker
UI_ONLY=false
REUSE_CACHE=false
if [ "${1:-}" = --ui-only ]; then NAME=docker-ui; UI_ONLY=true; fi
if [ "${1:-}" = --reuse-cache ]; then REUSE_CACHE=true; fi
"$ADB" shell rm -rf "/sdcard/Download/androidagent-$NAME-tests"
"$ADB" shell am instrument -w -e run_id "$RUN_ID" -e ui_only "$UI_ONLY" -e reuse_cache "$REUSE_CACHE" dev.mateuszsury.androidagent.tests/dev.mateuszsury.androidagent.DockerRuntimeTests
DEST="${ANDROIDAGENT_DOCKER_EVIDENCE_DIR:-artifacts/$NAME}"
mkdir -p "$DEST"
"$ADB" pull "/sdcard/Download/androidagent-$NAME-tests/." "$DEST/"
python3 - "$RUN_ID" "$DEST" <<'PY'
import json,pathlib,sys
result=json.loads((pathlib.Path(sys.argv[2])/'results.json').read_text())
assert result['run_id']==sys.argv[1], 'Stale Docker test report'
print(f"{len(result['tests'])} real Docker checks, {result['failures']} failures")
raise SystemExit(bool(result['failures']))
PY
