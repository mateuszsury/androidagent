# Embedded terminal packaging

Build the pinned terminal assets before packaging the APK:

```sh
python3 tools/prepare-terminal.py
python3 tools/check-terminal-assets.py
```

The host needs `dpkg-deb`, `patchelf`, Python and `pyelftools`. Downloads retain the
environment's proxy and certificate configuration. Existing verified downloads
are reused from `build-runtime/terminal`. The build never executes downloaded
Android package binaries or maintainer scripts on the host.

`--abi arm64-v8a` or `--abi x86_64` limits a build to one architecture. An explicit
`--update-lock` selects the current official bootstrap and package index; review
the lock, source recipe metadata, notices and runtime inventories after updating.
Both ABIs ship in the release APK. The runtime ZIPs are generated artifacts.

Each ABI manifest describes `runtime.zip`, SHA256, bytes, its ABI, deferred
symlinks as `{path, target}`, regular executable paths, JNI executable names,
native file hashes, selected package versions and bootstrap versions. The ZIP
contains regular files and directory entries under `usr/`. Extract into a fresh
versioned private directory, validate the archive digest, set executable modes,
then create the manifest symlinks. The app's persistent home and conversation
files belong outside that replaceable runtime directory.

The canonical prefix is `/data/data/com.termux/files/usr`. Start trusted JNI
PRoot with `PROOT_LOADER` set to its trusted JNI loader, `PROOT_TMP_DIR` set to a
real writable app-private directory, and bind the extracted `usr` to that
canonical prefix. Avoid automatically loading `termux-exec` with `LD_PRELOAD`:
PRoot already implements executable translation. `--kill-on-exit` is supported
by the pinned Termux PRoot 5.1.107.96. The musl libraries are isolated under
`usr/opt/androidagent/musl/lib` and can be bound to `/lib` for official native
Linux clients; client DNS and CA paths must also be provided.

The checker verifies exact archive/native SHA256 and length, unique safe paths,
the presence and executable modes of required tools, the 64-bit ELF architecture
and the complete runtime/JNI dynamic dependency closure. These host checks do
not establish that PRoot is allowed by a particular Android device's SELinux
policy, nor do they substitute for first-run tests with the app UID on Android.
