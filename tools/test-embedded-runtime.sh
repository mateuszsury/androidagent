#!/usr/bin/env bash
# Real app-UID embedded runtime; destructive install modes are restricted to an emulator.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SERIAL:?Set ANDROID_SERIAL to the dedicated emulator/device under test}"
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
ADB="$ANDROID_SDK_ROOT/platform-tools/adb"
MODE="${1:---existing}"
case "$MODE" in --existing|--clean|--upgrade) ;; *) echo 'Usage: test-embedded-runtime.sh --existing | --clean NEW.apk | --upgrade OLD.apk NEW.apk' >&2; exit 2 ;; esac
if [[ "$MODE" != --existing ]]; then
  [[ "$("$ADB" shell getprop ro.kernel.qemu | tr -d '\r')" == 1 ]] || { echo 'Clean/upgrade orchestration requires a disposable emulator.' >&2; exit 2; }
fi
ABI=$("$ADB" shell getprop ro.product.cpu.abi | tr -d '\r')
python3 - "$ABI" <<'PY'
import hashlib,json,os,pathlib,shutil,sys,urllib.request
abi=sys.argv[1]
lock=json.loads(pathlib.Path('app/src/main/assets/terminal/clients-lock.json').read_text())
cache=pathlib.Path('build-runtime/test-fixtures/embedded-clients'); cache.mkdir(parents=True,exist_ok=True)
for agent in ('codex','claude'):
    pin=lock[agent]['targets'][abi]
    archive=cache/(agent+'-'+abi+'.tgz')
    def verified(p):
        return p.is_file() and p.stat().st_size==pin['bytes'] and hashlib.file_digest(p.open('rb'),'sha256').hexdigest()==pin['sha256']
    if not verified(archive):
        archive.unlink(missing_ok=True)
        candidates=[pathlib.Path('/workspace/tools/codex-linux-x64.tgz' if agent=='codex' else '/workspace/tools/claude-native.tgz')]
        if os.environ.get('ANDROIDAGENT_CLIENT_ARCHIVE_DIR'):
            candidates.insert(0,pathlib.Path(os.environ['ANDROIDAGENT_CLIENT_ARCHIVE_DIR'])/archive.name)
        for candidate in candidates:
            if verified(candidate):
                try: os.link(candidate,archive)
                except OSError: shutil.copyfile(candidate,archive)
                break
    if not verified(archive):
        assert pin['url'].startswith('https://registry.npmjs.org/'), 'Unexpected official client archive source'
        part=archive.with_suffix('.part')
        try:
            with urllib.request.urlopen(pin['url'],timeout=60) as response,part.open('wb') as out:
                shutil.copyfileobj(response,out,65536)
            assert verified(part), 'Official archive SHA256/bytes mismatch'
            part.replace(archive)
        finally:
            part.unlink(missing_ok=True)
    print(agent,'verified official test archive:',pin['bytes'],'bytes SHA256',pin['sha256'])
PY
# Archives travel only through the local adb transport, keeping test APK small.
python3 -m http.server 18787 --bind 127.0.0.1 --directory build-runtime/test-fixtures/embedded-clients >build-runtime/test-fixtures/embedded-clients/fixture.log 2>&1 &
FIXTURE_PID=$!
cleanup() { "$ADB" reverse --remove tcp:18787 >/dev/null 2>&1 || true; kill "$FIXTURE_PID" >/dev/null 2>&1 || true; }
trap cleanup EXIT
python3 - <<'PY'
import time,urllib.request
for attempt in range(50):
    try:
        urllib.request.urlopen('http://127.0.0.1:18787/',timeout=1).close()
        break
    except OSError:
        time.sleep(.1)
else: raise SystemExit('Local client archive fixture did not start')
PY
kill -0 "$FIXTURE_PID"
"$ADB" reverse tcp:18787 tcp:18787
if [[ "${ANDROIDAGENT_EMBEDDED_USE_BUILT_TESTS:-false}" == true ]]; then
  [[ -s build/device-tests/tests.apk ]] || { echo 'Missing prebuilt tests APK.' >&2; exit 2; }
else
  bash tools/test-android.sh --build-only
fi
API=$("$ADB" shell getprop ro.build.version.sdk | tr -d '\r')
DEST="${ANDROIDAGENT_EMBEDDED_EVIDENCE_DIR:-artifacts/api$API/embedded-runtime}"
mkdir -p "$DEST"
run_phase() {
  local phase="$1" migration="$2" report_dir="$3"
  local run_id host_started
  run_id=$(python3 -c 'import uuid; print(uuid.uuid4())')
  host_started=$(python3 -c 'import datetime,time,json; print(json.dumps({"epoch_ms":time.time_ns()//1000000,"monotonic_ms":time.monotonic_ns()//1000000,"utc":datetime.datetime.now(datetime.timezone.utc).isoformat()}))')
  "$ADB" shell rm -rf /sdcard/Download/androidagent-embedded-tests
  "$ADB" shell am instrument -w -e run_id "$run_id" -e phase "$phase" -e migration "$migration" -e archive_origin http://127.0.0.1:18787 dev.mateuszsury.androidagent.tests/dev.mateuszsury.androidagent.EmbeddedRuntimeTests
  mkdir -p "$report_dir"
  "$ADB" pull /sdcard/Download/androidagent-embedded-tests/. "$report_dir/"
  python3 - "$run_id" "$report_dir" "$API" "$phase" "$host_started" <<'PY'
import datetime,json,pathlib,sys,time
r=json.load(open(sys.argv[2]+'/results.json'))
began=json.loads(sys.argv[5])
r['host_observation']={'started_utc':began['utc'],'completed_utc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'elapsed_ms':time.monotonic_ns()//1000000-began['monotonic_ms'],'guest_clock_delta_at_start_ms':r['started_epoch_ms']-began['epoch_ms'],'scope':'Software Android emulator guest clocks can drift; host timing does not represent physical ARM Android performance'}
pathlib.Path(sys.argv[2]+'/results.json').write_text(json.dumps(r,indent=2)+'\n')
assert r['run_id']==sys.argv[1], 'Stale embedded-runtime report'
assert r['api']==int(sys.argv[3]) and r['phase']==sys.argv[4], 'Wrong device/phase'
assert r['failures']==0 and all(t['pass'] for t in r['tests']), r
assert len(r['tests']) >= (1 if r['phase']=='seed' else 27), 'Incomplete suite'
assert r['phase']=='seed' or (r['version_code']>=403 and not r['debuggable']), 'Wrong release APK'
if r['phase']!='seed':
    for name in ('terminal.png','runtime.png'):
        assert (pathlib.Path(sys.argv[2])/name).stat().st_size>0, 'Missing current UI screenshot: '+name
print('Embedded-runtime',r['phase'],len(r['tests']),'checks passed; ABI:',r['supported_abis'],'elapsed ms:',r['elapsed_ms'])
PY
}
if [[ "$MODE" == --clean ]]; then
  : "${2:?Specify new APK}"
  "$ADB" uninstall dev.mateuszsury.androidagent >/dev/null 2>&1 || true
  "$ADB" uninstall com.termux >/dev/null 2>&1 || true
  "$ADB" install --no-incremental "$2"
elif [[ "$MODE" == --upgrade ]]; then
  : "${2:?Specify old APK}" "${3:?Specify new APK}"
  python3 - "$2" <<'PY'
import hashlib,pathlib,sys
p=pathlib.Path(sys.argv[1]); assert p.stat().st_size==460925925
h=hashlib.file_digest(p.open('rb'),'sha256').hexdigest()
assert h=='5300a530810554f5f693e5757e34e4683734b92c647b192f247c7f747aad80a0', 'Not the exact published 0.4.2 APK'
PY
  "$ADB" uninstall dev.mateuszsury.androidagent >/dev/null 2>&1 || true
  "$ADB" uninstall com.termux >/dev/null 2>&1 || true
  "$ADB" install --no-incremental "$2"
  "$ADB" install --no-incremental -r build/device-tests/tests.apk
  run_phase seed false "$DEST/upgrade-seed"
  "$ADB" install --no-incremental -r "$3"
fi
"$ADB" install --no-incremental -r build/device-tests/tests.apk
TERMUX_PACKAGE=$("$ADB" shell pm list packages com.termux | tr -d '\r')
if [[ "$TERMUX_PACKAGE" == *"package:com.termux"* ]]; then
  echo 'External Termux is present; embedded-only acceptance requires its absence.' >&2
  exit 1
fi
if [[ "$MODE" == --upgrade ]]; then run_phase verify true "$DEST/upgrade";
elif [[ "$MODE" == --clean ]]; then run_phase verify false "$DEST/clean";
else run_phase verify false "$DEST/existing"; fi
