# Local Docker

androidagent packages QEMU and an Alpine Linux guest with a real Docker Engine. The VM runs as the Android app UID with its own Linux kernel. Root, Shizuku and a separate Termux app are unnecessary. Agents use the separate embedded terminal; Docker remains optional.

## Build, preview and preserve a result

1. Open **Docker**, choose 512 MB/1 GB/2 GB RAM and 1 or 2 vCPUs, then start the environment. The first launch extracts its image; later launches reuse the disk.
2. Create a task or try the example app. The demo builds an interactive HTML page and a small server from the included `alpine:3.23` image as `USER 1000:1000`; it does not require a registry or model account.
3. Edit the task files, build its Dockerfile and run a container. Follow asynchronous operations in the log. Each task can publish one container port with its own phone URL (see [Published ports](#published-ports-and-wi-fi-access)); the first one keeps `http://127.0.0.1:18781`. Open it in the app's WebView preview.
4. Execute commands inside that task's container. `/workspace` is its project mount. Normal image startup preserves `WORKDIR`, `ENTRYPOINT` and `CMD`; write durable output under `/workspace`, for example `/workspace/out/app.apk`.
5. Export a result before cleanup. The app stores it outside the VM and exposes an Android sharing URI.
6. Clean up the selected task to remove its container, generated images and working files. Other tasks and exported results remain. Resetting the entire VM disk is a separate confirmed action.

Rerunning a task replaces its previous container. Project files remain; changes only inside the container must be copied to `/workspace` before replacement or cleanup.

## Agent tools

Both Codex and Claude Code receive these 15 MCP tools. The owner normally starts the VM from the app. `docker_start`/`docker_stop` work only after the owner enables developer mode (the same opt-in as `run_command`); otherwise they return an error asking the agent to involve the user. Agents are not given the Docker socket or a guest administrator shell.

| Tool | Operation |
| --- | --- |
| `docker_status` | VM readiness, architecture, engine and task status; `previews` lists each published app's `url`, `host_port`, `container_port` (and `lan_url`) |
| `docker_start` | Start the VM: `memory_mb` 512–2048, `cpus` 1–2; returns immediately, then poll `docker_status` |
| `docker_stop` | Gracefully stop the VM: cancel operations, stop containers, sync the disk |
| `docker_diagnostics` | Saved startup evidence by byte `offset`/`limit`, at most 32768 bytes per response |
| `docker_create` | Create a task and project directory |
| `docker_write`, `docker_read`, `docker_files` | UTF-8 files and directory listings |
| `docker_build` | Build the Dockerfile asynchronously |
| `docker_run` | Run the task image or an OCI image detached; `port` publishes it and returns `url`, `host_port`, `container_port` |
| `docker_exec` | Execute an asynchronous task-container command; a timeout stops only that command |
| `docker_logs` | Operation status, exit result or container logs |
| `docker_cancel` | Cancel an operation, stop its container and unpublish its port |
| `docker_export` | Preserve a file in Android storage and obtain a sharing URI |
| `docker_cleanup` | Clean up that task only |

Build/run/exec return operation IDs: poll with `docker_logs`. The initial result is not a completed build. A task accepts one active operation at a time.

`docker_start` launches the same foreground service as the panel. Android 12+ may refuse to start a foreground service while androidagent is in the background (for example when an agent runs behind another app); the tool then fails with that reason and the owner starts the VM on the Docker screen. A battery-optimisation exemption usually allows the start.

## Published ports and Wi-Fi access

QEMU user networking forwards only what Android asks for. The control API (`127.0.0.1:18780` → guest 7080) and the legacy preview (`127.0.0.1:18781` → guest 8080) are fixed at boot. Every `docker_run` with `port=P`:

1. The guest agent gives the task one guest port `G` from 8080–8179 (the lowest free one, so the first task keeps 8080) and stores it in the task's `task.json`; a rerun keeps it. The container is started with `-p G:P`.
2. Android forwards a host port to `G`. Loopback `G = 8080` reuses `18781`; other ports are added at runtime through the QMP socket with `human-monitor-command` → `hostfwd_add net tcp:127.0.0.1:H-:G`, where `H` is taken from 18800–18899 (preferring `18800 + G − 8080`; a host port held by another app is skipped). slirp reports failures as returned text, which the app checks.
3. `docker_run` and `docker_status` return `{"url": "http://127.0.0.1:H", "host_port": H, "container_port": P}`. The preview screen accepts the `port` and `title` extras (default 18781).

`docker_cancel`, `docker_cleanup` or a run without `port` release the guest port and remove the forward (`hostfwd_remove net tcp:127.0.0.1:H`). Stopping the VM ends all forwards; after a restart, the status heartbeat restores forwards for tasks that still hold a port. At most 100 tasks can publish a port at once.

**Wi-Fi access** is an owner setting, off by default (`lan_preview` in the `docker_vm` preferences, `DockerVm.setLanExposure`). When it is on, preview forwards bind `0.0.0.0` instead of loopback (the first task then uses `18800`, because `18781` stays loopback-only) and results add `lan_url` with the phone's Wi-Fi, Ethernet or hotspot IPv4 address. The change applies to running forwards within a few seconds.

Security: with Wi-Fi access on, **anyone on the same network can open every published container app without authentication**, including its APIs and any data it serves. Use it on trusted networks only and turn it off afterwards. Untrusted networks (cafés, hotels, company guest Wi-Fi) may also scan for these ports. The control API, phone MCP and USB/UART bridges always stay on loopback and keep their tokens.

## Isolation and limits

Containers receive only their own project mount, without `--privileged` or the Docker socket. A selected USB grant adds one device node; a UART grant adds the read-only protocol module. Non-root image users are supported. This is standard Docker isolation inside a VM, not a guarantee against kernel or QEMU vulnerabilities.

| Resource | Limit |
| --- | --- |
| Project text file | 256 KiB |
| Build context | 512 MiB, without symlinks |
| Exported file | 128 MiB |
| Operation output | Last 64000 characters |
| Operation timeout | 15 minutes of guest time; an exec timeout stops only that command, never the task's server |
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

0.4.2 → 0.4.3 → 0.4.4 retain the same base-disk/kernel hashes and persistent QCOW2. Changes to `docker/guest/agent.py` (such as per-task preview ports and exec-only timeouts) ship only after `tools/prepare-docker-runtime.py` re-embeds the runtime into each ABI's initramfs; the same migration then applies. The changed initramfs replaces only owned runtime code under `/opt/androidagent`; it does not reset task files, Docker layers, terminal HOME or exports. Interrupted runtime replacement is retried at next boot. Android validates base/kernel compatibility before updating initramfs.

0.4.2 changed the base relative to 0.4.1. An incompatible base is not silently swapped beneath an overlay; export data and explicitly reset if necessary. New full guest rebuilds can also change base compatibility. Exported Android files survive VM reset.

## USB and validation

[USB](USB.md) explains raw-device passthrough and authenticated UART bridges. USB ownership applies to all operations on its assigned Docker task. Detach, revoke, Stop and cleanup stop the using container; restart does not restore device grants. Direct USB tools remain available without Docker.

[Building](BUILDING.md) prepares both guest architectures and the native libraries. `tools/test-docker.sh` runs a real engine on a dedicated emulator and **resets its disk**. `--ui-only` also uses that destructive test fixture; `--reuse-cache` is only for controlled retries and still resets at the final case. ARM-host scripts support `--host-clock`; their Linux guest is not physical Android.

`tools/test-docker-startup.py` can migrate an exact0.4.3/0.4.2 initramfs baseline on the same disk, inject containerd/dockerd delays and apply host CPU quotas. See [Testing](TESTING.md) for actual timings and the later physical Lenovo acceptance, including a real interactive WebView preview. Sources/licenses: [docker/SOURCES.md](../docker/SOURCES.md).
