# Embedded terminal sources and client downloads

The terminal uses upstream Termux packages inside androidagent's own private
storage and UID. Installing `com.termux` or calling a separate `RUN_COMMAND`
service is unnecessary. This integration uses the Termux package/runtime
environment; it does not bundle a second Termux APK or change the application ID.

## Android execution and package prefix

Official Termux packages are built for `/data/data/com.termux/files/usr`. Setting
`PREFIX` or `HOME` alone does not relocate their compiled paths. PRoot binds this
canonical prefix and home to androidagent's private terminal directories.

Android 10 and later prohibit direct `execve` of writable app data for apps
targeting API 29 or later. The entry point and PRoot loader are packaged as native
libraries extracted by Android's package manager. PRoot intercepts child
`execve` system calls, executes the packaged loader and maps the target program
and its ELF interpreter. The integration retains androidagent's current target
SDK; it does not depend on the target SDK 28 exception used by upstream Termux.

Relevant upstream implementation and documentation:

- [Termux filesystem layout and fixed prefix](https://github.com/termux/termux-packages/wiki/Termux-file-system-layout).
- [PRoot execution interception](https://github.com/termux/proot/blob/v5.1.107.96/src/execve/enter.c):
  `get_loader_path` supports `PROOT_LOADER`; `translate_execve_enter` redirects
  execution to the loader. `PROOT_LOADER` must refer to the real extracted native
  library path, not a virtual path inside the PRoot binding.
- [PRoot loader](https://github.com/termux/proot/blob/v5.1.107.96/src/loader/loader.c):
  maps executable segments and starts the program. A 32-bit loader is only
  necessary when executing 32-bit programs; this release supports 64-bit ABIs.
- [Termux execution restrictions and system linker execution](https://github.com/termux/termux-exec-package/blob/v2.5.0/site/pages/en/projects/docs/technical/index.md):
  the linker solution cannot execute static programs and does not intercept
  direct system calls, for which upstream recommends PRoot.
- [AOSP untrusted app SELinux policy](https://android.googlesource.com/platform/system/sepolicy/+/refs/heads/main/private/untrusted_app_all.te):
  allows `self:process ptrace` and executable mapping of app data. These platform
  rules do not grant root or access to other applications' private data. OEM
  behavior still requires physical Android validation.

Native entry points must be tested from the release application's actual UID and
SELinux domain. An `adb shell` execution or host Linux result is not equivalent.
PRoot is a path and syscall compatibility layer, not a security boundary.

## Upstream software and licenses

Package download URLs, versions and SHA256 hashes belong in the terminal runtime
lock and client lock. Corresponding upstream package build recipes are available
in [termux/termux-packages](https://github.com/termux/termux-packages), and official
bootstrap construction is available in
[termux/termux-packages bootstrap scripts](https://github.com/termux/termux-packages/tree/master/scripts/bootstrap).

- PRoot: [Termux fork](https://github.com/termux/proot), GPL-2.0; retain copyright,
  license and corresponding source/version information for redistributed ELF.
- Bash: [GNU Bash](https://www.gnu.org/software/bash/), GPL-3.0; Termux recipe and
  patches are in `packages/bash`.
- Node.js: [Node.js](https://nodejs.org/), MIT and bundled third-party notices;
  Termux recipe is in `packages/nodejs-lts`. Current Node LTS package no longer
  includes npm; npm must be selected separately.
- Python: [Python](https://www.python.org/), PSF license and included notices;
  Termux recipe is in `packages/python`.
- musl: [musl libc](https://musl.libc.org/), MIT; the runtime uses a pinned official
  Alpine package and preserves its source and license information.
- Codex: [openai/codex](https://github.com/openai/codex), Apache-2.0.
- Claude Code: proprietary Anthropic software. Its npm `LICENSE.md` states:
  “© Anthropic PBC. All rights reserved. Use is subject to the Legal Agreements
  outlined here: https://code.claude.com/docs/en/legal-and-compliance.” The APK
  does not redistribute Claude Code. Automatic setup downloads the pinned
  unmodified package directly from the official npm registry on the user's
  device. Account login remains an explicit owner action through the official
  client. No developer or build-machine authentication tokens are bundled.

This implementation does not copy the GPL-3.0 Termux application Java code.
Upstream Termux's application-level license is described in its
[LICENSE.md](https://github.com/termux/termux-app/blob/master/LICENSE.md); binary
package licenses still apply independently.

## Pinned official clients

[`terminal/clients-lock.json`](../terminal/clients-lock.json) pins complete npm
tarballs by exact length and SHA256. Both supported ABI archives were downloaded
for verification on 2026-10-11; their bytes additionally matched npm's published
SHA1 and SHA512 integrity fields. These host checks establish package integrity
and ELF dependencies, not successful Android execution or account authentication.

| Client | Version | ABI | Compressed bytes | Expected executable |
| --- | --- | --- | ---: | --- |
| Codex | 0.162.1 | arm64-v8a | 157869533 | `package/vendor/aarch64-unknown-linux-musl/bin/codex` |
| Codex | 0.162.1 | x86_64 | 165453522 | `package/vendor/x86_64-unknown-linux-musl/bin/codex` |
| Claude Code | 2.1.296 | arm64-v8a | 112659894 | `package/claude` |
| Claude Code | 2.1.296 | x86_64 | 112639151 | `package/claude` |

The official Codex Linux packages are published as suffix versions of
`@openai/codex` (`0.162.1-linux-arm64` and `0.162.1-linux-x64`), not as independent
`@openai/codex-linux-*` package versions. Both selected musl Codex executables
have no `DT_NEEDED` libraries; the arm64 executable has no dynamic section.

Claude's selected executables request `/lib/ld-musl-aarch64.so.1` or
`/lib/ld-musl-x86_64.so.1` and depend solely on the corresponding
`libc.musl-*.so.1`. The pinned musl runtime must provide both interpreter paths
and library aliases inside PRoot. The npm wrapper's Android platform branch
selects a separate `*-android` package, which is not published in the selected
release's optional dependencies. The application therefore runs the verified
musl executable directly instead of relying on the wrapper's platform detection.

Linux/musl clients must not inherit a Bionic `LD_PRELOAD` library. Their virtual
filesystem needs a usable CA certificate bundle and `/etc/resolv.conf`; Android
DNS configuration is provided by the active network rather than a normal Linux
`/etc/resolv.conf`. Shell and tools remain subject to the Android application UID
and existing permission/approval bridge.
