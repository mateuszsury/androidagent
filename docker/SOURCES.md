# Runtime sources and licenses

androidagent's Java integration, Python guest controller and build scripts retain
the project's Apache-2.0 license. The APK is an aggregate of separately executing
open-source programs; their own licenses apply to their binaries and sources.
The root `LICENSE` does not relicense QEMU, Linux, Alpine or their dependencies.

## QEMU and Android shared libraries

- QEMU 11.1.2, GPL-2.0, official Termux binary packages. Exact download filenames,
  versions and SHA256: `qemu-lock.json`. The actually packaged ELF files and their
  original paths: `qemu-libraries-arm64-v8a.json` / `qemu-libraries-x86_64.json`.
- Upstream source: https://download.qemu.org/qemu-11.1.2.tar.xz
  SHA256: `731b5681e4bb18be313231579b8efd0296c5b015fa36dc533874b639ba838016`.
- Termux QEMU recipe and Android patches:
  https://github.com/termux/termux-packages/tree/14ef1032809e1d885bf120b1ee03d751e3727325/packages/qemu-system-x86-64-headless
- Package recipe snapshot:
  https://github.com/termux/termux-packages/tree/14ef1032809e1d885bf120b1ee03d751e3727325
  Package recipes contain upstream source URLs, hashes, patches, build options and
  dependency declarations. Their component licenses differ. Existing package
  copyright/license notices are copied to `licenses/termux` and into APK assets.
- Our binary transformations are fully specified by `tools/prepare-qemu.py`:
  packaging names, RPATH, SONAME, version requirements and ELF page alignment.
  We do not alter QEMU's machine emulation code.
- x86 firmware is supplied by Termux's qemu-common package. SeaBIOS sources and
  license: https://github.com/coreboot/seabios. Network option ROMs are disabled;
  there is no PXE/EFI network boot requirement for the directly loaded kernel.

## Linux guest and Alpine packages

- Alpine 3.23 base images are pinned by per-architecture OCI digest in
  `tools/build-docker-guest.sh`. Actual package versions, component license SPDX
  identifiers, upstream homepages, origins and exact Alpine aports build commits
  are recorded in `guest-packages-*.json`.
- Linux `linux-virt` 6.18.55-r0 is GPL-2.0-only with the Linux syscall exception.
  Complete upstream kernel source is Linux 6.18 plus stable patch 6.18.55:
  https://cdn.kernel.org/pub/linux/kernel/v6.x/linux-6.18.tar.xz and
  https://cdn.kernel.org/pub/linux/kernel/v6.x/patch-6.18.55.xz.
- Exact kernel build recipe, local patches and ARM64/x86_64 configurations:
  https://github.com/alpinelinux/aports/tree/a485504610a948f6ef7b76b1dc02388c9be9bb03/main/linux-lts
  The unmodified recipe and its SHA512 source list are also in `linux-APKBUILD`.
- Other package recipes, source archives and patches can be retrieved from the
  aports commit recorded per package, under `main/<origin>` or
  `community/<origin>`: https://github.com/alpinelinux/aports.
  Alpine distfiles mirror: https://distfiles.alpinelinux.org/distfiles/v3.23/.
- Docker Engine/CLI 29.5.2, Buildx and Compose: Apache-2.0. Python: PSF-2.0.
  musl: MIT. BusyBox: GPL-2.0-only. All remaining packages retain the licenses
  recorded by their installed APK metadata. Package metadata is an inventory,
  not a replacement for the complete upstream license text/source requirements.

The license texts live in `licenses/` and are bundled in
`assets/docker/licenses/`. GPL components and their corresponding build recipes
must stay available with binary distribution; dependency source archives should
be retained alongside any public release. No cloud proxy certificate, model
account, access token, SSH password, or provider client/model binary is included
in the guest image.

Linxr and Pockr informed the architecture research. Their source code and binary
assets are not included in this implementation.
