# Testing and release evidence

This page describes the signed **0.4.4 / versionCode 404** release and later physical-device acceptance, rather than treating all environments or observations as interchangeable.

## Exact release

| Field | Value |
| --- | --- |
| Package | `dev.mateuszsury.androidagent` |
| APK bytes | `653558148` |
| APK SHA256 | `c939d53f01be01eb32be96b306c7cdf4bc97854b69de7c994d13af689bd37e8a` |
| Certificate SHA256 | `b0fe60ab6fb41c33051f1fe9c16b2d24e476fdf66be225870db831cd7e2fb66b` |
| Supported ABIs | arm64-v8a / x86_64 |
| Android | minimum API 30, target API 36 |

[Release v0.4.4](https://github.com/mateuszsury/androidagent/releases/tag/v0.4.4) contains the APK, checksums, immutable build-time index and later device reports. The build-time index precedes tablet acceptance: its physical-validation/model flags describe that earlier time. Later reports below supplement it; they do not change APK bytes or rewrite that evidence.

## Physical Lenovo TB336FU

**Android 16/API 36, ARM64,4 KiB pages, update without clearing data.** The VM used1 GB RAM and 2 vCPUs. Terminal and full Docker cycle passed: cold boot, actual containerd/Docker readiness and Alpine import, build/run/exec/logs/HTTP, interactive WebView, export, cancel, persisted files/image after stop/start and task cleanup while preserving exports. Both VMs ended with exit0 following requested stop. Conversation history and existing Accessibility services were preserved.

| Measurement | Observed time |
| --- | ---: |
| First boot, supervisor readiness | 75.801 s |
| First ready observed from host recording start | 127.3 s |
| Restart, supervisor / host ready observation | 83.082 s / 162.6 s |
| Build / run / exec | 55.69 s / 26.37 s / 14.26 s |

The raw report has 49 passing observations and one explicitly superseded assertion against cached status. These are not49 independent test cases. QA argument-name, premature-status-read and Android/data evidence-write errors are preserved in the report.

A later owner-authenticated Codex phase completed **three real model turns**: arithmetic result, context recall and actual `device_status` / `docker_status` calls. Metrics were independently checked through ADB; replies persisted after reopening. Docker was stopped during that model status call—the model did not perform the full build cycle. The default model's exact ID was not extracted. Claude account-backed responses and physical OTG accessories were not tested.

Primary evidence:

- [Physical summary JSON](https://github.com/mateuszsury/androidagent/releases/download/v0.4.4/lenovo-0.4.4-report.json), [raw device results](https://github.com/mateuszsury/androidagent/releases/download/v0.4.4/lenovo-0.4.4-device-results.json) and [device evidence ZIP](https://github.com/mateuszsury/androidagent/releases/download/v0.4.4/lenovo-0.4.4-evidence.zip).
- [Authenticated Codex report](https://github.com/mateuszsury/androidagent/releases/download/v0.4.4/lenovo-0.4.4-model-report.json) and [model evidence ZIP](https://github.com/mateuszsury/androidagent/releases/download/v0.4.4/lenovo-0.4.4-model-evidence.zip).
- [Original checksum list](https://github.com/mateuszsury/androidagent/releases/download/v0.4.4/SHA256SUMS-0.4.4.txt) and [screenshots](SCREENSHOTS.md).

0.4.4 was tested physically as an update. Actual no-separate-Termux bootstrap/HTTPS client setup on the tablet was reported during0.4.3; a clean0.4.4 installation was separately exercised on the emulator.

## Build-time host and emulator results

| Suite | Passed | Scope |
| --- | ---: | --- |
| Python host regressions | 117/117 | No skips; processes, protocols, deadlines and cleanup |
| Connector helper | 8/8 | Separate offline transfer/journal tests |
| ARM Linux/QEMU | 45/45 | Real engine, migration and slow startup, not Android |
| API 30 app/migration/USB/Docker UI/WebView | 145/145 | 83+4+40+10+8 |
| API 36 app/migration/USB/Docker UI/WebView | 147/147 | 84+4+41+10+8 |
| 0.4.3→0.4.4 update, each API | 15/15 | 3 old-version seed+7 preservation+5 diagnostics |
| Clean0.4.4 without com.termux, API 36 | 28/28 | Real embedded tools, PTY, clients and UI |
| Extra API 30 navigation / API 36 Stop diagnostics | 10/10 /1/1 | Separate from canonical suites |

Canonical Android checks total 350:344 on the new APK and 6 old-version seed checks. Extra diagnostic checks total 11. The51-test Docker-supervision subset is already counted in 117 host tests. The 45 ARM checks comprise 3 base/kernel-integrity,5 baseline 0.4.3 and 37 runtime0.4.4 checks. Failed/interrupted attempts are not counted as final passes.

Emulators were x86_64 API 30/36,3072/4096 MB RAM,2 vCPUs,4 KiB pages and 720×1480/dpi 300, software TCG without KVM and the guarded PHADDD fix. Their guest clocks drift: reports record independent host timings. They are not phone performance benchmarks.

Clean setup launched Bash, Node 24.18.0, Python 3.14.6, curl/jq/git, shebang/PTY, child-process cancellation, Codex0.162.1 and Claude2.1.296 under the release app UID. Genuine pinned client archives were delivered by a local adb-reverse fixture; this does not prove Android HTTPS or account login. WebView tests exercised real DOM/JS/canvas/storage/error/recovery. After200 diagnostic cycles without forced GC, FD counts remained48→48/API 30 and 77→77/API 36.

USB suites use real JNI/adbd plus explicit UART/ESP/SAF fixtures. They do not certify physical accessories, usbredir transport, power or firmware flashing.

## ARM guest startup regression

QEMU 10.1.5 hosted Linux 6.18.55/aarch64, Docker 29.5.2, containerd 2.2.0 and overlay2 on x86_64 Linux with host clock. Same-disk upgrade at 1 GB/1 vCPU/0.25 host CPU, with 20 s containerd and 160 s dockerd delays, became ready after 311.192 host seconds. Its actual Docker ping/info phase took206.287 s, beyond the old120 s cap. Restart took67.307 s; a clean2 vCPU/0.5 CPU/import boot took134.916 s.

Real build/run/exec/cgroup/logs/HTTP/export/cancel/cleanup and persisted project/image/container/logs were verified. HTTP in that Linux test did not execute JavaScript; Android WebView and later physical-device tests provide separate evidence. CPU quotas/delays are not a calibrated Lenovo benchmark.

## Earlier failures and retained records

Physical 0.4.2 hit dockerd's managed-containerd timeout. Physical 0.4.3 reached containerd Version RPC but exceeded the supervisor's120-second Docker cap. A later3.94-second xtables operation and neutral process-death log did not establish a deadlock or Android kill.0.4.4 adds [bounded supervision and diagnostics](DOCKER.md#startup-supervision-and-diagnostics).

The first API 30 instrumentation attempt had an input-dispatch ANR after 45 passing checks. No decisive ANR trace was available; its cause remains unknown. Ten exact navigation repetitions and the final83-check normal suite passed on unchanged APK bytes. The initial API 36 Stop test waited for three conditions while asserting four; V2 waited for all original conditions under the same 30-second deadline and passed. The original false condition was not captured, so a precise cause is not claimed. Controller/export and interrupted infrastructure attempts are retained too.

Raw logs, reports, earlier reviews and transfer journals were removed from the current source tree during public-repository cleanup. They remain byte-for-byte in the [versioned 0.4.4 evidence snapshot](https://github.com/mateuszsury/androidagent/tree/6f5b3f566a4a85e0e10a5946de3afa55a8079c05/artifacts), its [test documentation](https://github.com/mateuszsury/androidagent/blob/6f5b3f566a4a85e0e10a5946de3afa55a8079c05/docs/TESTING.md), and release assets. The original Lenovo0.4.3 log is 32044 bytes with SHA256 `4920d8d3acbb43990be42d13ac41ff0d162fd9ff3811fead4d25245bd4d7d9c6`.

## 0.5.0 development checks

The redesign, background services and new phone tools were checked on an **Android 11 x86_64 software (TCG) emulator** with a debuggable development build that packages the published 0.4.4 terminal runtime. This is development evidence for the 0.5.0 changes, gathered before the release build:

- Host suite: `python3 -m unittest discover -s tests -p 'test_*.py'` and `tools/check-docs.py` pass; `tests/android` compiles against the app.
- The embedded environment initialized from a clean install in about 15 minutes of emulated time. In the terminal and in services, Python 3.14.6, Node.js v24.18.0 and Git 2.56.0 ran as the app UID without root, and ANSI colours rendered.
- Over MCP with the installation token, `tools/list` returned 92 tools; `device_status` reported LAN/IP fields; `notify`, `set_volume`/`get_volume`, `list_apps` and `docker_status` returned expected results; `torch` reported that the emulator has no flashlight.
- `service_start` showed the approval card in the chat; after tapping **Approve** (`Zatwierdź`) a `python3 -m http.server` service reported its open port and served a page through `adb forward`. It kept serving after leaving the app and after the global **Stop**, which also closed agent tool access.
- Every redesigned screen was captured in the dark theme; selected screens also in the light theme and with large fonts.
- Release gate: the signed 0.5.0 APK itself (SHA256 `6582371368ed7c0212ea1939827cf4e7eab30734445c46bb7910622de50cfa5d`), built by the [release workflow](../.github/workflows/release.yml) with the refreshed Termux runtime, passed `tools/test-embedded-runtime.sh --clean` with 28 checks and 0 failures on the same Android 11 x86_64 emulator before publication, including the official Codex and Claude clients without login. The workflow's Android 15 emulator did not boot for this release because the runner user could not open `/dev/kvm`; the workflow now grants that access.

Not verified for 0.5.0: the other instrumentation suites (`tools/test-android.sh` and others), a physical device, logged-in Codex or Claude conversations, booting the Docker VM, the new Docker port forwards on Android's QEMU, and the notification access, location, photo, camera, APK install and file share tools on a device.

## Run checks yourself

Use [Building](BUILDING.md) for the host and instrumentation toolchain. Destructive suites belong on a dedicated emulator. Reports must match the current UUID, API, exact app bytes and instrumentation identity; stale exports are not new passes. Do not label `/status`, a mock protocol stream or `--version` as an authenticated model task.
