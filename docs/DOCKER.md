# Local Docker

androidagent packages QEMU and an Alpine Linux guest with a real Docker Engine. The VM runs as the Android app UID with its own Linux kernel. Root, Shizuku and a separate Termux app are unnecessary. Agents use the separate embedded terminal; Docker remains optional.

## Build, preview and preserve a result

1. Open **Docker**, choose 512 MB/1 GB/2 GB RAM and 1 or 2 vCPUs, then start the environment. The first launch extracts its image; later launches reuse the disk.
2. Create a task or try the example app. The demo builds an interactive HTML page and a small server from the included `alpine:3.23` image as `USER 1000:1000`; it does not require a registry or model account.
3. Edit the task files, build its Dockerfile and run a container. Follow asynchronous operations in the log. One published preview uses `http://127.0.0.1:18781`; open it in the app's WebView.
4. Execute commands inside that task's container. `/workspace` is its project mount. Normal image startup preserves `WORKDIR`, `ENTRYPOINT` and `CMD`; write durable output under `/workspace`, for example `/workspace/out/app.apk`.
5. Export a result before cleanup. The app stores it outside the VM and exposes an Android sharing URI.
6. Clean up the selected task to remove its container, generated images and working files. Other tasks and exported results remain. Resetting the entire VM disk is a separate confirmed action.

Rerunning a task replaces its previous container. Project files remain; changes only inside the container must be copied to `/workspace` before replacement or cleanup.

## Agent tools

Both Codex and Claude Code receive these 13 MCP tools. The owner starts the VM from the app; agents are not given the Docker socket or a guest administrator shell.

| Tool | Operation |
| --- | --- |
| `docker_status` | VM readiness, architecture, engine and task status |
| `docker_diagnostics` | Saved startup evidence by byte `offset`/`limit`, at most 32768 bytes per response |
| `docker_create` | Create a task and project directory |
| `docker_write`, `docker_read`, `docker_files` | UTF-8 files and directory listings |
| `docker_build` | Build the Dockerfile asynchronously |
| `docker_run` | Run the task image or an OCI image, optionally with a preview port |
| `docker_exec` | Execute an asynchronous task-container command |
| `docker_logs` | Operation status, exit result or container logs |
| `docker_cancel` | Cancel an operation and stop its container |
| `docker_export` | Preserve a file in Android storage and obtain a sharing URI |
| `docker_cleanup` | Clean up that task only |

Build/run/exec return operation IDs: poll with `docker_logs`. The initial result is not a completed build. A task accepts one active operation at a time.

## Isolation and limits

Containers receive only their own project mount, without `--privileged` or the Docker socket. A selected USB grant adds one device node; a UART grant adds the read-only protocol module. Non-root image users are supported. This is standard Docker isolation inside a VM, not a guarantee against kernel or QEMU vulnerabilities.

| Resource | Limit |
| --- | --- |
| Project text file | 256 KiB |
| Build context | 512 MiB, without symlinks |
| Exported file | 128 MiB |
| Operation output | Last 64000 characters |
| Operation timeout | 15 minutes of guest time |
| Default container | 512 MB, 1 CPU, 256 PIDs; RAM configurable up to 2 GB |
| Container log | 2 × 5 MB rotation |
| VM disk | 8 GB virtual capacity, allocated progressively |

File operations reject absolute paths, traversal, device files, directories as file targets and symlinks, using component-by-component `openat`/`O_NOFOLLOW` checks. Guest HTTP is forwarded to `127.0.0.1:18780` with a separate random VM token. Phone MCP uses port 8766 and different credentials. WebView enables JavaScript without a Java bridge or app-file/content-URI access; external navigation opens the browser.

The VM uses a foreground service and wake lock. Android or the OEM can still end it. Unexpectedly interrupted operations are marked `interrupted`; the stored disk remains. Global Stop revokes agent access and ends QEMU. Normal panel shutdown first synchronizes the disk. Sudden termination can lose unwritten data or damage image cache; export recoverable projects before a confirmed reset.

QEMU TCG is slower and more battery intensive than desktop virtualization. Containers must match the guest architecture; cross-architecture binfmt is not supplied. Many desktop Android SDK binaries are x86_64-only, so arbitrary APK-building Docker images cannot be promised on ARM64. Buildx/Compose CLI are in the guest, but UI/MCP tasks currently manage one container rather than arbitrary Compose, Kubernetes or Docker Desktop features.

## Startup supervision and diagnostics

Version 0.4.3 fixed dockerd's short managed-containerd timeout by separately supervising containerd and waiting for the real Version gRPC response before `dockerd --containerd=...`. A socket pathname alone is not readiness. On physical Lenovo0.4.3, containerd became ready, but the supervisor's old 120-second Docker phase ended initialization too early. Later xtables latency logs did not establish a deadlock or external Android kill.

Revision3 in 0.4.4 uses absolute deadlines and these bounded budgets:

| Stage | Seconds |
| --- | ---: |
| containerd, shared across at most two attempts | 180 |
| Docker `_ping` and `/info` | 360 |
| Image probe / import / tag | 15 / 180 / 60 |
| Disk sync | 60 |
| Total guest startup | 900 |
| Physical Android outer startup | 1200 |

A containerd retry requires confirmed process exit and retains the original budget. Readiness also requires the image, sync and a live supervisor PID/start time. An old ready marker is insufficient. Diagnostic-write failures, including ENOSPC/EIO, do not skip owned process cleanup.

Each boot records UUID, UTC/monotonic clocks, stage timings, API attempts, PIDs/exits, CPU/IO/RSS/wchan, relevant firewall processes and actual xtables-lock state. Guest storage retains five boots under `/var/lib/androidagent/diagnostics/<uuid>`: six logs bounded to 512 KiB, an event journal up to 1 MiB and `startup.json`. Android retains bounded startup evidence too. Console draining is independent of log compaction and continues on log-write errors.

Choose **Save startup diagnostics** (`Zachowaj diagnostykę rozruchu`) or MCP `docker_diagnostics` during startup, after errors or after reopening. The authenticated guest diagnostics operation works before ready and outside the task lock, accepts only fixed logs, caps output at 256 KiB and redacts the known VM token. Counters are observations, not substitutes for readiness.

Physical devices use a host clock. Android software emulators use `icount shift=3` for nested VM emulation; their timings are not phone benchmarks.

## Data migration

0.4.2 → 0.4.3 → 0.4.4 retain the same base-disk/kernel hashes and persistent QCOW2. The changed initramfs replaces only owned runtime code under `/opt/androidagent`; it does not reset task files, Docker layers, terminal HOME or exports. Interrupted runtime replacement is retried at next boot. Android validates base/kernel compatibility before updating initramfs.

0.4.2 changed the base relative to 0.4.1. An incompatible base is not silently swapped beneath an overlay; export data and explicitly reset if necessary. New full guest rebuilds can also change base compatibility. Exported Android files survive VM reset.

## USB and validation

[USB](USB.md) explains raw-device passthrough and authenticated UART bridges. USB ownership applies to all operations on its assigned Docker task. Detach, revoke, Stop and cleanup stop the using container; restart does not restore device grants. Direct USB tools remain available without Docker.

[Building](BUILDING.md) prepares both guest architectures and the native libraries. `tools/test-docker.sh` runs a real engine on a dedicated emulator and **resets its disk**. `--ui-only` also uses that destructive test fixture; `--reuse-cache` is only for controlled retries and still resets at the final case. ARM-host scripts support `--host-clock`; their Linux guest is not physical Android.

`tools/test-docker-startup.py` can migrate an exact0.4.3/0.4.2 initramfs baseline on the same disk, inject containerd/dockerd delays and apply host CPU quotas. See [Testing](TESTING.md) for actual timings and the later physical Lenovo acceptance, including a real interactive WebView preview. Sources/licenses: [docker/SOURCES.md](../docker/SOURCES.md).
