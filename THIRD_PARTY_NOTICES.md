# Third-party software and notices

The root [Apache-2.0 license](LICENSE) applies to androidagent's application integration code. It does not relicense separately bundled programs, dependencies, fonts, firmware or downloaded provider clients. Original copyright/license texts and pinned source records are retained.

| Component | License / status | Attribution and sources |
| --- | --- | --- |
| Buddy Android | Apache-2.0 | [NOTICE](NOTICE), [upstream](https://github.com/ghorbelhamdi/buddy-android), original base8c69699b1338f3b3c177979bbd0a69140bbdb9ce |
| Geist / Geist Mono | SIL OFL1.1 | [Original font notice](app/src/main/assets/fonts/OFL-Geist.txt) |
| Material Symbols | Apache-2.0 | Preserved upstream icon assets and [Google source](https://github.com/google/material-design-icons) |
| usb-serial-for-android3.11.0 | MIT | [License](usb/licenses/usb-serial-MIT.txt), [pinned sources/adaptations](usb/README.md) |
| esp-serial-flasher | Apache-2.0 | [License](usb/licenses/esp-flasher-Apache-2.0.txt), [vendored source](usb/native/vendor/esp) |
| QEMU / SeaBIOS | GPL-family and component licenses | [Docker sources](docker/SOURCES.md), [runtime notices](docker/licenses), [QEMU lock](docker/qemu-lock.json) |
| libusb / usbredir | LGPL-family component licenses | [USB bridge notes](usb/README.md), [Docker library inventories](docker/SOURCES.md) |
| Linux / BusyBox / Alpine packages | GPL and other per-package licenses | [Guest sources/recipes](docker/SOURCES.md), package inventories and [license texts](docker/licenses) |
| Termux userspace / PRoot / Bash / tools | Per-package licenses, including GPL | [Terminal sources](terminal/SOURCES.md), [recipes](terminal/recipes), [notices](terminal/licenses) |
| musl / GCC runtime libraries | MIT / GCC runtime licenses and exception | [Source records](terminal/SOURCES.md), [shared notices](terminal/licenses/shared) |
| Codex CLI | Apache-2.0; downloaded on device | [OpenAI source](https://github.com/openai/codex), [pinned client lock](terminal/clients-lock.json) |
| Claude Code | Proprietary Anthropic software; downloaded on device | [Official terms](https://code.claude.com/docs/en/legal-and-compliance), [download provenance](docs/TERMINAL-SOURCES.md) |

The app embeds Termux **packages**, not the GPL-3.0 Termux Android application's Java code or a second APK. PRoot is a separately executed GPL-2.0-only program. Its compatibility mappings do not grant root. Provider model weights, account credentials and provider clients are not bundled in the APK; official clients are fetched by the user's device.

## Distributing binaries

Retain the root license/NOTICE, component copyright and license texts, and applicable source/build records when redistributing. GPL/LGPL components have their own corresponding-source and other obligations. Pinned upstream locations, versions, source hashes, recipes and Android adaptations are documented in [terminal/SOURCES.md](terminal/SOURCES.md) and [docker/SOURCES.md](docker/SOURCES.md). A source link or inventory alone is not a blanket claim of satisfying every binary-distribution obligation: distributors must provide the applicable complete sources, patches and build scripts under those licenses.

Runtime packaging copies the original notices into APK assets. Large upstream source archives are not claimed to be embedded in the APK. Do not remove license directories as generated clutter. Integration code changes do not change the upstream licenses.

For an attribution or licensing correction, open an issue with the component/version and the relevant source or license notice. Security-sensitive disclosures should use [Security](SECURITY.md).
