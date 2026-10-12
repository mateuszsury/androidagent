# androidagent — Codex and Claude Code for Android

**Codex and Claude Code on your Android device.** A native chat interface, an embedded terminal, phone tools, optional Docker environments, and USB access in one application.

[![Release](https://img.shields.io/github/v/release/mateuszsury/androidagent)](https://github.com/mateuszsury/androidagent/releases/latest)
[![Checks](https://github.com/mateuszsury/androidagent/actions/workflows/quality.yml/badge.svg)](https://github.com/mateuszsury/androidagent/actions/workflows/quality.yml)
[![License](https://img.shields.io/badge/app%20license-Apache--2.0-blue)](LICENSE)
[![Android](https://img.shields.io/badge/Android-11%2B-green)](docs/BUILDING.md)

<p align="center">
  <img src="docs/images/home.png" width="245" alt="androidagent home screen with chat, phone tools and Docker" />
  <img src="docs/images/docker.png" width="245" alt="Docker screen in dark mode with memory and CPU controls" />
  <img src="docs/images/runtime.png" width="245" alt="Embedded environment with Codex and Claude Code installed" />
</p>

An independent community fork of [Buddy](https://github.com/ghorbelhamdi/buddy-android). It is not an official OpenAI or Anthropic application. The application UI currently contains Polish and upstream English labels; project documentation is in English. See the [screenshot gallery](docs/SCREENSHOTS.md).

## Install

**[Download androidagent 0.4.4](https://github.com/mateuszsury/androidagent/releases/download/v0.4.4/androidagent-0.4.4.apk)** · [Release notes and checksums](https://github.com/mateuszsury/androidagent/releases/tag/v0.4.4)

Requires **Android 11 or later**, **arm64-v8a or x86_64**. The APK is approximately 623 MiB because it includes both terminal runtimes and Linux VM images. Allow at least 1 GB of additional free space for terminal setup and more for Docker projects.

1. Install the APK. Updating an existing installation with the same signing certificate preserves conversations, settings and files.
2. Open **Environment and accounts** (`Środowisko i konta`). The embedded shell, Python, Node.js, Git and tool bridge initialize automatically. Select Codex, Claude Code or both, then choose **Prepare selected agents** (`Przygotuj wybranych agentów`). The app downloads the official pinned clients, verifies their hashes and launches them to check installation.
3. Use **Log in to Codex** or **Log in to Claude Code**. Complete the official account flow shown in the embedded terminal. Environment setup and account login are separate steps.
4. Start a conversation. Phone status and `/status` work without a model account. Open **Permissions** for optional screen control, microphone, notifications and background settings.

No separate Termux installation, manual `pkg`/`npm` setup, root or computer is required for normal use. Docker is optional. Shizuku integration is not implemented.

## What you can do

| Feature | Available functionality |
| --- | --- |
| Chat | Native conversation history, drafts, attachments, model selection, light/dark themes and selectable code |
| Agents | Official Codex CLI **0.162.1** and Claude Code **2.1.296**, separate sessions, resume, tool progress, official login and error handling |
| Phone tools | Battery, RAM, storage and network status; approved commands; screen reading and gestures through optional Accessibility |
| Terminal and files | Embedded interactive shell and PTY, Python/Node/Git, project files, command output, cancellation and exit status |
| Docker | Local Linux VM, image builds, containers, exec/logs, interactive WebView previews, exports, cancellation and task cleanup |
| USB | Direct UART, ESP flashing, ADB to a second Android device, SAF storage, raw USB passthrough and UART bridges to Docker |

## Access and control

The terminal and agents run as **androidagent's Android UID**. They do not gain root or access to other apps' private storage. Accessibility requires the owner's system approval. USB devices and SAF folders require explicit grants and conversation assignment.

Normal mode restricts the agents' shell and file tools. Developer mode enables coding operations across the application's private environment; it is not a project sandbox. The MCP `run_command` tool additionally requires approval of the exact command. **Stop** revokes the active run's tool access and cancels its commands. Docker uses a separate Linux kernel and task directories, without exposing the Docker socket to agents.

Models are remote services: this app runs their clients locally, not model weights. Provider access, subscriptions and available models depend on your account. See [permissions](docs/PERMISSIONS.md), [agent access](docs/CLAUDE.md) and [architecture](docs/ARCHITECTURE.md).

## Validation

Version 0.4.4 has passed Android 11/16 emulator suites and a physical **Lenovo TB336FU, Android 16 ARM64** update with a real Docker build/run/exec/WebView/export/restart/cleanup cycle. A later owner-authenticated Codex test completed three real conversation turns and observed `device_status` and `docker_status` calls.

Claude account-backed responses and physical USB OTG accessories remain unverified. Emulator UART/ESP/storage fixtures are not hardware certification. Exact results, earlier failures, timings and evidence links are in [Testing](docs/TESTING.md).

## Documentation and development

- [Build from source](docs/BUILDING.md) — toolchain, generated runtimes and signing.
- [Docker](docs/DOCKER.md), [USB](docs/USB.md), [Claude Code](docs/CLAUDE.md) and [permissions](docs/PERMISSIONS.md).
- [Architecture](docs/ARCHITECTURE.md), [terminal provenance](docs/TERMINAL-SOURCES.md) and [WebView/emulator troubleshooting](docs/WEBVIEW.md).
- [Contributing](CONTRIBUTING.md), [support](SUPPORT.md), [security reports](SECURITY.md) and [changelog](CHANGELOG.md).

The source checkout contains code, pinned manifests, recipes and license notices. Generated APKs, runtime binaries, raw logs and transfer journals are kept out of the current source tree; releases and the versioned evidence snapshot retain validation records. Start with the [documentation index](docs/README.md).

## License and attribution

Application integration code is licensed under **[Apache-2.0](LICENSE)**. [NOTICE](NOTICE) preserves Buddy attribution. Bundled components have their own licenses, including GPL/LGPL runtime programs; the app license does not relicense them. See [Third-party notices](THIRD_PARTY_NOTICES.md) for components, source locations and distribution obligations. Claude Code is proprietary and is downloaded directly from Anthropic rather than bundled in the APK.
