#!/usr/bin/env bash
# True install-r preservation of an existing 0.4.3 instance; never uninstalls/resets data.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the dedicated emulator}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
: "${1:?Usage: test-update-preservation.sh OLD043.apk NEW044.apk}"
: "${2:?Specify new 0.4.4 APK}"
OLD_APK="$1"
NEW_APK="$2"
TEST_APK="${ANDROIDAGENT_UPDATE_TEST_APK:-build/device-tests/tests.apk}"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
APP_PACKAGE=dev.mateuszsury.androidagent
[[ -s "$TEST_APK" ]] || { echo 'Build the instrumentation APK separately before this harness.' >&2; exit 2; }
[[ -s "$NEW_APK" ]] || { echo 'Provide the already built signed 0.4.4 APK before seeding this test.' >&2; exit 2; }
[[ "$("$ADB" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'This fixture adds test-owned project/history records and requires a dedicated emulator.' >&2; exit 2; }
python3 - "$ADB" "$OLD_APK" <<'PY'
import hashlib,pathlib,subprocess,sys
old=pathlib.Path(sys.argv[2])
assert old.stat().st_size==653533571
assert hashlib.file_digest(old.open('rb'),'sha256').hexdigest()=='92edfd2974159fd523cfa6a7d0a6bd3235deb0cdfe62ab5df42a3c3567ae7537', 'Not exact published 0.4.3'
source=subprocess.run([sys.argv[1],'shell','pm','path','dev.mateuszsury.androidagent'],capture_output=True,text=True,check=True).stdout
paths=[line.removeprefix('package:').strip() for line in source.splitlines() if line.startswith('package:')]
assert len(paths)==1, 'Requires an already installed single-APK 0.4.3; do not reinstall/reset it as a substitute'
installed=subprocess.run([sys.argv[1],'shell','sha256sum',paths[0]],capture_output=True,text=True,check=True).stdout.split()[0]
assert installed=='92edfd2974159fd523cfa6a7d0a6bd3235deb0cdfe62ab5df42a3c3567ae7537', 'Existing installed APK is not the exact 0.4.3 baseline'
print('Exact installed 0.4.3 verified; data and VM disk will be retained.')
PY
API=$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')
DEST="${ANDROIDAGENT_UPDATE_EVIDENCE_DIR:-artifacts/0.4.4/api$API/update-preservation}"
mkdir -p "$DEST"
"$ADB" install --no-incremental -r "$TEST_APK"
run_phase() {
  local phase="$1" expected_code="$2" expected_checks="$3" tested_apk="$4" host_started run_id
  run_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
  host_started=$(python3 -c 'import datetime,time,json; print(json.dumps({"utc":datetime.datetime.now(datetime.timezone.utc).isoformat(),"monotonic_ms":time.monotonic_ns()//1000000}))')
  "$ADB" shell rm -rf /sdcard/Download/androidagent-update-preservation-tests
  "$ADB" shell am instrument -w -e run_id "$run_id" -e update_phase "$phase" dev.mateuszsury.androidagent.tests/dev.mateuszsury.androidagent.DockerMigrationTests
  mkdir -p "$DEST/$phase"
  "$ADB" pull /sdcard/Download/androidagent-update-preservation-tests/. "$DEST/$phase/"
  python3 - "$DEST/$phase/results.json" "$run_id" "$expected_code" "$expected_checks" "$tested_apk" "$TEST_APK" "$host_started" <<'PY'
import datetime,hashlib,json,os,pathlib,sys,time
p=pathlib.Path(sys.argv[1]);r=json.loads(p.read_text());start=json.loads(sys.argv[7])
def artifact(path,commit):
    f=pathlib.Path(path)
    return {'name':f.name,'bytes':f.stat().st_size,'sha256':hashlib.file_digest(f.open('rb'),'sha256').hexdigest(),'source_commit':commit}
r['tested_apk']=artifact(sys.argv[5],os.environ.get('ANDROIDAGENT_UPDATE_OLD_COMMIT' if r['phase']=='seed043' else 'ANDROIDAGENT_UPDATE_NEW_COMMIT'))
r['tested_instrumentation']=artifact(sys.argv[6],os.environ.get('ANDROIDAGENT_UPDATE_TEST_COMMIT'))
r['host_observation']={'started_utc':start['utc'],'completed_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'elapsed_ms':time.monotonic_ns()//1000000-start['monotonic_ms'],'scope':'Android software emulator; guest clock/elapsed time and performance differ from physical ARM Android'}
r['update_method']='adb install --no-incremental -r; old app data and disk retained; no uninstall or VM reset'
p.write_text(json.dumps(r,indent=2)+'\n')
assert r['run_id']==sys.argv[2], 'Stale update report'
assert r['version_code']==int(sys.argv[3]), 'Wrong installed version'
assert len(r['tests'])==int(sys.argv[4]), 'Incomplete update/diagnostic suite'
assert r['failures']==0 and all(t['pass'] for t in r['tests']), r
print(r['phase'],len(r['tests']),'checks passed; actual UID:',r['process_uid'],'host ms:',r['host_observation']['elapsed_ms'])
PY
}
run_phase seed043 403 3 "$OLD_APK"
"$ADB" install --no-incremental -r "$NEW_APK"
run_phase verify044 404 7 "$NEW_APK"
# Isolated log fixtures remain explicitly separate from real disk/update evidence.
run_phase diagnostics044 404 5 "$NEW_APK"
