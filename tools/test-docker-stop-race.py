#!/usr/bin/env python3
"""One real duplicate-start/Stop regression; preserve the product APK and app data."""
import argparse
import datetime
import hashlib
import json
import os
import pathlib
import queue
import subprocess
import threading
import time
import uuid

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--test-apk", required=True)
parser.add_argument("--product-apk", default="artifacts/androidagent-0.4.4.apk")
parser.add_argument("--evidence-dir")
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parent.parent
os.chdir(root)
adb = [os.environ.get("ANDROID_SDK_ROOT", "/workspace/android-sdk") + "/platform-tools/adb", "-s", args.serial]


def run(command):
    return subprocess.run(adb + command, capture_output=True, text=True, check=True, timeout=180).stdout


def artifact(path):
    item = pathlib.Path(path)
    return {"name": item.name, "bytes": item.stat().st_size,
            "sha256": hashlib.file_digest(item.open("rb"), "sha256").hexdigest()}


assert run(["shell", "getprop", "ro.kernel.qemu"]).strip() == "1", "Dedicated emulator required"
api = int(run(["shell", "getprop", "ro.build.version.sdk"]).strip())
product = artifact(args.product_apk)
assert product["bytes"] == 653558148 and product["sha256"] == "c939d53f01be01eb32be96b306c7cdf4bc97854b69de7c994d13af689bd37e8a", "Immutable 0.4.4 product mismatch"
product["source_commit"] = "bc73465374bf6b5df91ec00726d1ff58d38b9567"
installed = run(["shell", "pm", "path", "dev.mateuszsury.androidagent"]).strip().removeprefix("package:")
assert run(["shell", "sha256sum", installed]).split()[0] == product["sha256"], "Wrong installed product"
test = artifact(args.test_apk)
test["source_commit"] = os.environ.get("ANDROIDAGENT_STOP_TEST_COMMIT")
run(["install", "--no-incremental", "-r", args.test_apk])
ident = str(uuid.uuid4())
out = pathlib.Path(args.evidence_dir or f"artifacts/0.4.4/api{api}/docker-stop-diagnostics/{ident}")
out.mkdir(parents=True, exist_ok=False)
run(["shell", "rm", "-rf", "/sdcard/Download/androidagent-docker-stop-race-tests"])
began = {"utc": datetime.datetime.now(datetime.timezone.utc).isoformat(), "monotonic_ms": time.monotonic_ns() // 1000000}
command = adb + ["shell", "am", "instrument", "-w", "-e", "run_id", ident,
                 "-e", "stop_race_only", "true",
                 "dev.mateuszsury.androidagent.tests/dev.mateuszsury.androidagent.DockerRuntimeTests"]
events = []
print("START", args.serial, ident, began["utc"], flush=True)
with (out / "host-driver.jsonl").open("w") as log:
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    lines = queue.Queue()
    def read_lines():
        try:
            for line in process.stdout: lines.put(line)
        finally: lines.put(None)
    threading.Thread(target=read_lines, daemon=True).start()
    try:
        deadline = time.monotonic() + 600
        while True:
            if time.monotonic() >= deadline:
                (out / "host-timeout-service.txt").write_text(run(["shell", "dumpsys", "-t", "2", "activity", "services", "dev.mateuszsury.androidagent/dev.mateuszsury.androidagent.DockerService"]))
                raise TimeoutError("Narrow stop probe exceeded 600 host seconds; service dump retained")
            try: line = lines.get(timeout=1)
            except queue.Empty: continue
            if line is None: break
            observation = {"host_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                           "host_monotonic_ms": time.monotonic_ns() // 1000000, "line": line.rstrip("\n")}
            log.write(json.dumps(observation, ensure_ascii=False) + "\n"); log.flush()
            print(line, end="", flush=True)
            if "STOP_RACE_EVENT " in line:
                event = json.loads(line.split("STOP_RACE_EVENT ", 1)[1])
                event["host_utc"] = observation["host_utc"]
                event["host_monotonic_ms"] = observation["host_monotonic_ms"]
                events.append(event)
        code = process.wait(timeout=30)
        assert code == 0, f"Instrumentation transport failed: {code}"
    finally:
        if process.poll() is None:
            process.terminate()
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
run(["pull", "/sdcard/Download/androidagent-docker-stop-race-tests/.", str(out)])
report = json.loads((out / "results.json").read_text())
report["tested_apk"] = product
report["tested_instrumentation"] = test
report["host_observation"] = {"started_utc": began["utc"], "completed_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                              "elapsed_ms": time.monotonic_ns() // 1000000 - began["monotonic_ms"],
                              "events": events, "scope": "Software Android emulator; host receipt timestamps and both guest clocks recorded independently"}
report["fixture"] = "One actual DockerService/QEMU start/stop. No model, guest Docker Engine readiness or physical ARM proof. Product/app data not reset."
(out / "results.json").write_text(json.dumps(report, indent=2) + "\n")
assert report["run_id"] == ident and report["api"] == api and report["stop_race_only"], "Stale or wrong diagnostic report"
assert len(report["tests"]) == 1, "Not the narrow stop regression"
print("RESULT", out, "failures", report["failures"], flush=True)
raise SystemExit(bool(report["failures"]))
