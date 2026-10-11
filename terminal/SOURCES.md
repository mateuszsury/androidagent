# Embedded terminal: sources and licenses

Androidagent embeds an official Termux userspace for `arm64-v8a` and `x86_64`.
It does not embed another Android APK, install `com.termux`, or communicate with
the external Termux `RUN_COMMAND` service. Programs execute as Androidagent's UID.
These terminal packages retain their component licenses; the project's Apache-2.0
license does not relicense them. PRoot is GPL-2.0-only and is a separately executing
program, not a library linked into the application's Java code.

`runtime-lock.json` pins the official bootstrap release, published bootstrap
SHA256, package filenames, versions, SHA256 and complete declared dependency
closure. The selected extra `libmount` dependency repairs an upstream
`util-linux` package metadata omission: its `lsns` executable needs `libmount.so`.
`inventory-*.json` also records the bootstrap's installed package versions, the
final runtime archive SHA256 and every transformed JNI file's source/output hash.
Bootstrap components not overlaid by the selected packages remain at their
recorded bootstrap versions. The runtime includes Python and pip, Node.js LTS
and npm, Bash, Git, curl, jq, archive tools and their dependencies.

Termux packages are built for `/data/data/com.termux/files/usr`. Androidagent
preserves this virtual prefix with a PRoot bind to its own private runtime
directory. The official unmodified ELF programs remain inside the runtime
archive. Only PRoot, its static exec loader and the two shared-library
dependencies are repackaged as Android-executable `libaaterm_*.so` files, isolated
from QEMU's JNI library names. `tools/prepare-terminal.py` specifies all binary
transformations: dependency aliases, SONAME/RPATH, ELF version requirements and
page alignment. The loader itself is copied without modification.

The bootstrap source and packaging are maintained at:

- https://github.com/termux/termux-packages/tree/d8c12528580df83e04869bcd81a6d6f17455faf3
- https://github.com/termux/termux-packages/tree/d8c12528580df83e04869bcd81a6d6f17455faf3/scripts/bootstrap
- https://github.com/termux/proot/tree/v5.1.107.96

`recipes-metadata.json` records the pinned recipe paths, recipe SHA256, upstream
source/license fields and Git blob SHA of all files in each recipe directory.
`recipes/` retains the entry recipes, while the pinned complete Git tree above
provides the Android patches and build infrastructure. Recipes specify upstream
source URLs and hashes. These references are source locations, not a claim that
all large upstream source archives are embedded in the APK. Distributors must
retain the corresponding sources, applicable patches and build scripts and make
them available under the licenses of the programs they distribute.

The complete license texts and notices supplied by the official bootstrap and
packages are copied to `licenses/<abi>` and bundled as terminal asset licenses.
The bootstrap contains `share/LICENSES`; some package notices are absolute
symlinks into that directory and are not host-resolvable. Those shared license
texts are copied in full, and the original notice symlinks remain in the runtime.
Development-only sanitizer test executables under `libexec/installed-tests` and
the unused 32-bit PRoot companion loader are omitted. License documents remain.
Package control, file-list and installed-version metadata are updated during
packaging without running package maintainer scripts on the host.

The optional Linux userspace libraries used by downloaded native clients come
from pinned Alpine 3.23 packages: musl 1.2.5-r23 (MIT), libgcc and libstdc++
15.2.0-r2 (GCC runtime library licenses with the Runtime Library Exception).
Their exact archives, SHA256 and aports recipe commits are in `runtime-lock.json`.
Musl is placed at `usr/opt/androidagent/musl` and is separate from Bionic/Termux
libraries. Its corresponding source is musl 1.2.5; the complete musl copyright
and GCC runtime license texts are in `licenses/shared`, with their pinned source
locations and hashes in `license-sources.json`.

- https://github.com/alpinelinux/aports/tree/8aef0c37b0ad23dc4137f0e4755b97a59dc698b8/main/musl
- https://github.com/alpinelinux/aports/tree/7fa6e7fd3465c5ff89cc33a56d20bf4d59105aaf/main/gcc
- https://musl.libc.org/releases/musl-1.2.5.tar.gz
- https://ftp.gnu.org/gnu/gcc/gcc-15.2.0/gcc-15.2.0.tar.xz

Official provider clients are not bundled. `clients-lock.json` and
`docs/TERMINAL-SOURCES.md` describe their pinned download provenance, platform
constraints and licensing. In particular, Claude Code is proprietary and is
downloaded from Anthropic's official npm packages on the user's device. Runtime
preparation and client installation do not authenticate an account. No model
account, token, computer configuration, cloud certificate or secret is included.
