# Changelog

Public APKs and their immutable checksums are in [GitHub Releases](https://github.com/mateuszsury/androidagent/releases). This file summarizes user-facing changes; detailed validation has its own [evidence page](docs/TESTING.md).

## Unreleased

- English documentation, real screenshots and contributor/support/security guidance.
- Current embedded-runtime instructions and physical 0.4.4 results replace older setup guidance.
- Generated logs, transfer journals and obsolete planning pages removed from the current source tree; original evidence retained in the versioned snapshot and releases.
- GitHub issue/PR forms and quick documentation/host checks.
- Visual redesign in the Claude Code style: warm near-black dark theme by default, cream text, coral `#D97757` accent, monospace chrome and hairline borders. Every screen, including the floating bubble, is restyled and UI labels are unified to Polish.
- Animated coral spark with rotating Polish verbs while the agent works; screen transitions and motion follow the system "remove animations" setting.
- Chat transcript like Claude Code: `›` prompt markers, dot-gutter replies, `Tool(args)` rows with state dots and expandable `└` result previews, permission-style approval cards and TodoWrite plans shown as checklists.
- Full Markdown rendering of agent replies, including live streaming: headings, emphasis, links, nested and task lists, quotes, rules, GitHub tables, syntax-highlighted fenced code with diff colours, and a copy button.
- Structured tool results for Claude (stream-json) and Codex (command, MCP and file-change items), stored with each conversation.
- Robustness: MCP event callbacks run on their own thread pool so blocking approvals cannot starve them; at most six chats run at once, with a clear message; a watchdog finishes runs whose helper process ended without a completion event.
- Background services (`Usługi w tle`): long-running commands survive the agent's turn and Stop, with per-service logs, restart policy, autostart, a foreground service with wake and Wi-Fi locks, and URLs for 127.0.0.1 and the Wi-Fi address. `device_status` reports LAN IP addresses.
- Phone tools without Accessibility: `confirm` approval cards, real Android notifications, `wait`, `open_url`, `open_app`, `list_apps` and `wait_for_install` now work without the Accessibility service.
- New intent tools: `start_intent` (with a deny-list), `share_text`, `open_settings`, `set_alarm`, `set_timer`, `add_calendar_event`, `dial` and `compose_sms`. Dial and SMS only open the dialer or composer and never call or send.
- New device tools: `set_volume`, `get_volume`, `media_key`, `torch`, `clipboard_set`, `clipboard_get`, `speak`, `set_brightness` and `vibrate`. `press` gains `lock_screen`, `take_screenshot` and `power_dialog`; `read_screen` gains resource IDs and paging, and `find_and_tap` is added.
- Workspace file tools (`files_list`, `file_info`, `open_file`, `share_file`, `save_to_downloads`, `install_apk`, `install_status`). The Android app skill now installs APKs with `install_apk` instead of the separate Termux app, and the Files screen (`Pliki środowiska`) can open, share, install and save files.
- Notification access (`notifications_list`, `notification_action`), `get_location`, and `take_photo`, `recent_photos`, `import_photo`, with new consent rows on the Permissions screen (`Uprawnienia`).
- Docker: each task gets its own published port and preview URL (host ports 18800–18899; the first task keeps `127.0.0.1:18781`), an off-by-default Wi-Fi exposure setting, `docker_start`/`docker_stop` in developer mode, and exec timeouts that no longer stop the whole container. The preview screen is restyled.
- Terminal screen renders ANSI colours.

## 0.4.4 — 2026-10-12

- Bounded Docker startup budgets for slow ARM/TCG, absolute probes and confirmed-process retry rules.
- Persistent startup diagnostics, readonly pre-ready/error access, MCP diagnostic export and 1/2 CPU selection.
- Process/service ownership and FD/retention fixes; existing VM disk, projects and conversations preserved.
- Later physical Lenovo update passed the full Docker cycle and owner-authenticated Codex conversation/status-tool tests.

## 0.4.3 — 2026-10-12

- Embedded Termux userspace and app-UID terminal, without a separate Termux APK or manual package setup.
- Automatic pinned official client installation, progress/retry and owner login through the embedded terminal.
- Separate supervised containerd startup and real readiness probes.
- Permission-state guide and Android foreground-service behavior coverage.
- Physical clients/tools passed; Docker exceeded the then-current supervisor budget, addressed in 0.4.4.

## 0.4.2 — 2026-10-11

- Notification/USB service handling, attachment recreation, conversation bounds and cancellation fixes.
- Verified software-emulator PHADDD workaround restoring WebView tests.
- Improved Docker disk synchronization and retained data coverage.

Earlier milestones include the native Buddy-derived chat interface, Codex/Claude integration, local Docker, direct USB and reliability reviews. Original notes are available in the [historical documentation snapshot](https://github.com/mateuszsury/androidagent/tree/6f5b3f566a4a85e0e10a5946de3afa55a8079c05/docs).
