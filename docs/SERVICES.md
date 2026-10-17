# Background services

A coding agent's turn is one `buddy-run ask` process started under PRoot with `--kill-on-exit`. When the turn ends, PRoot kills everything the turn started, so `&`, `nohup`, `setsid` or `tmux` cannot keep a web server or bot alive. Background services are the supported way to run something long-lived: a server, a bot, a file watcher.

## How it works

| Part | Role |
| --- | --- |
| `ServiceManager` | Registry of named services in `files/services/registry.json`; starts, stops, restarts and monitors them |
| `EmbeddedRuntime.serviceCommand` | Builds the same PRoot command line, environment, `HOME` and `PATH` as the terminal, running [`termux/service-launch.sh`](../termux/service-launch.sh) |
| `ServicesService` | Foreground service (`specialUse`) with a partial wake lock and a Wi-Fi lock while at least one service runs; notification **Usługi w tle: N działają** with **Zatrzymaj wszystkie** |
| `ServicesBoot` | Process-start hook that restores autostart services; retries when a screen becomes visible |
| `ServicesActivity` | **Usługi w tle** screen: state, URLs, logs, start/stop, remove and **Dodaj usługę** |
| `ServiceTools` | MCP tools `service_start`, `service_list`, `service_status`, `service_logs`, `service_stop`, `service_restart`, `service_remove` |

Each service is its own PRoot process tree, started by the app process rather than by an agent run. The launcher changes to the working directory (default `~/.buddy/work`, `~` expands to the terminal home), exports `ANDROIDAGENT_SERVICE`, `PORT` (when declared) and approved extra variables, closes stdin and `exec`s `bash -c COMMAND`. The command must stay in the foreground: when it exits, PRoot ends the rest of the tree.

A definition holds the name (`[a-z0-9-]{1,32}`), exact command, working directory, optional port (1024–65535), autostart flag, restart policy (`never` or `on-failure`), environment variables, creation time and the owner (`chat:<id>`, `owner` for the screen, or `external`).

- **States:** `starting`, `running` (port open or alive for 2 s), `exited` with its code, `failed` (could not start, or restart limit reached) and `stopped`.
- **Restarts:** `on-failure` restarts a non-zero exit after 1, 2, 4, 8 and 16 s; after 5 restarts within 10 minutes the service is `failed`.
- **Stop:** SIGTERM to the service's processes, SIGKILL to the whole PRoot tree after 5 s.
- **Logs:** stdout and stderr go to `files/services/NAME.log`, rotated into `NAME.log.1` at 512 KB. `service_logs` returns at most 64 KB.
- **Autostart:** an autostart service that was running when the app stopped is started again when the app process starts. A service stopped by the owner or an agent stays stopped. Android may refuse a foreground service from the background; autostart then waits until a screen of the app is visible. If the embedded runtime is not prepared, autostart waits for it.

## Ports and addresses

On Android 10+ apps cannot read `/proc/net/tcp`. The manager uses it when it is readable, matching LISTEN sockets to the service's processes through `/proc/PID/fd`. Otherwise it probes the declared port on `127.0.0.1`. Without a declared port, it probes common development ports (3000, 5000, 5173, 8000, 8080 and others) during the first minute. A server bound only to `127.0.0.1` refuses the phone's own LAN address and is reported as `loopback_only`.

Local IPv4 addresses come from `ConnectivityManager` link properties, with `NetworkInterface` as a fallback for the phone's own hotspot. URLs are `http://127.0.0.1:PORT` on the phone and `http://WIFI-IP:PORT` for devices on the same Wi-Fi, hotspot or Ethernet. Mobile-data addresses are not offered because carriers do not route inbound connections. `device_status` also reports `wifi_connected`, `lan_ipv4`, `ip_addresses` and `background_services_running`.

## Approval and Stop

`service_start` requires developer mode and the owner's approval of the exact definition. The request uses the same in-chat card as `run_command` and shows the name, directory, port, autostart and restart settings, environment and the verbatim command. Restarting the same definition needs no new approval; a changed command asks again. Services added on the **Usługi w tle** screen are the owner's own action. `service_stop`, `service_restart` and `service_remove` require developer mode. `service_list`, `service_status` and `service_logs` are read-only.

Services are owner-approved and long-lived, so they are outside every agent Stop:

- The end of an agent turn and the chat's **Stop** end only that run's PRoot tree.
- The global emergency Stop (`McpHost.emergencyStop` → `EmbeddedRuntime.emergencyStop`) ends `helperProcesses`, terminal sessions and approved one-off commands. It revokes agent control for 60 s, which also blocks the service tools, but running services keep running.
- **Zatrzymaj wszystkie** in the notification and **Zatrzymaj wszystkie usługi** on the screen stop every service. Each card has its own **Zatrzymaj**.

The screen is opened with `new Intent(context, ServicesActivity.class)`; the notification opens it too.

## Limits

At most 16 services. Commands are limited to 8 KB. Services run as the app UID inside the same PRoot compatibility environment as the agents; they are not sandboxed from the app's private files. A service ends when Android kills the app process (for example after **Force stop**); `ServicesService` is sticky, so Android may recreate it and restore autostart services. The dev build has no terminal runtime, so services there fail with **Środowisko nie jest przygotowane**.
