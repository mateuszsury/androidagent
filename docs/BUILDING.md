# Build from source

The verified build path is the Linux script toolchain: `aapt2 → javac → d8 → apksigner`. A source checkout does not include generated terminal archives, QEMU libraries or Linux disk images. Build these assets before packaging the APK.

## Requirements

- Linux x86_64 with a working local Docker daemon and enough storage for two runtime images, caches and SDK tools.
- JDK **21**, Python **3.12** (tested; 3.11+ for the full tools/test suite), `dpkg-deb`, `binutils`/`readelf`, `patchelf` and Python `pyelftools`.
- Android SDK: `platforms;android-36`, `build-tools;35.0.0`, `platform-tools`.
- Android NDK **29.0.14206865** for ARM64/x86_64 USB JNI.
- For emulator tests: Android Emulator and an x86_64 system image. KVM is preferred; [software TCG](WEBVIEW.md) is also supported with documented limitations.

```sh
git clone https://github.com/mateuszsury/androidagent.git
cd androidagent
export ANDROID_SDK_ROOT=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-21
export ANDROID_NDK_ROOT="$ANDROID_SDK_ROOT/ndk/29.0.14206865"
python3 -m venv .venv
. .venv/bin/activate
python3 -m pip install -r requirements-dev.txt
```

Install the host packages with your distribution's package manager. Keep normal HTTPS certificate verification and any inherited proxy/CA configuration enabled.

## Prepare and build

```sh
python3 tools/prepare-qemu.py
python3 tools/prepare-terminal.py
bash tools/build-usb-native.sh
bash tools/build-docker-guest.sh x86_64
bash tools/build-docker-guest.sh aarch64
python3 tools/check-usb-assets.py
python3 tools/check-terminal-assets.py
python3 tools/check-docker-assets.py
bash tools/build-local.sh
```

Output: `artifacts/androidagent-0.5.0.apk`. The version is read from [BuildInfo.java](../app/src/main/java/dev/mateuszsury/androidagent/BuildInfo.java). Both supported ABIs ship in the release.

Terminal and QEMU packages are pinned in their lock files. Do not use `--update-lock` for a normal build. Review changed recipes, hashes, dependency closure and notices when deliberately updating a lock. Alpine package repositories can change even when the base OCI digest is pinned: rebuilding the VM is not a promise of byte-identical output. Generated asset manifests identify the actual bytes packaged.

For a runtime-only update with existing compatible Docker assets, `python3 tools/prepare-docker-runtime.py` updates the owned initramfs payload while retaining the base disk and kernel. A full guest rebuild may change that base. Never present a newly rebuilt APK as the old release under the same version.

## Signing

The local builder creates a development key under `~/.androidagent-keys` if none is supplied. Set `KEYSTORE` to use an existing key; the script reads the password from the sibling `password` file and uses the `buddy` alias. Keep both outside Git. Instrumentation must use the same certificate as the installed app.

An APK signed with a different key cannot update an existing release installation. Maintainer release keys are not distributed. The published 0.5.0 certificate SHA256 is:

```text
67857a20ad476b9d66e947e0501b9f1f787ba44c6289b5d0645e2994742e8fab
```

Releases up to 0.4.4 used certificate `b0fe60ab6fb41c33051f1fe9c16b2d24e476fdf66be225870db831cd7e2fb66b`, so 0.5.0 cannot update them in place.

Verify your own output with `apksigner verify --print-certs`. Checksums for published APKs are in [GitHub Releases](https://github.com/mateuszsury/androidagent/releases).

Android Studio/Gradle configuration is retained from upstream and uses the same version and asset gates. The verified release path is the script above. Gradle signing uses `BUDDY_KEYSTORE`, `BUDDY_KEYSTORE_PASSWORD`, `BUDDY_KEY_ALIAS` and optionally `BUDDY_KEY_PASSWORD`; without them, `assembleRelease` is unsigned.

## Checks

```sh
python3 tools/check-docs.py
python3 -m unittest discover -s tests -p 'test_*.py' -v
export KEYSTORE="$HOME/.androidagent-keys/development.jks"
export ANDROID_SERIAL=emulator-5554
adb install -r artifacts/androidagent-0.5.0.apk
bash tools/test-android.sh
bash tools/test-usb.sh
bash tools/test-webview.sh
```

Device suites change settings, create fixtures and may reset application or Docker data. Run them on a dedicated emulator, not your daily phone. `tools/test-embedded-runtime.sh --clean APK` removes the installed app and verifies setup without `com.termux`; its upgrade mode uses an exact old 0.4.2 APK and also resets test data. `tools/test-update-preservation.sh` separately verifies a real 0.4.3 → 0.4.4 in-place update. Full Docker tests reset the VM disk. See [Testing](TESTING.md) before running these suites.

GitHub Actions runs quick documentation/host checks and a separate application build/emulator workflow. CI-generated APKs use ephemeral development signing and are not maintainer releases.

Source and license details: [terminal](../terminal/README.md), [Docker sources](../docker/SOURCES.md), [USB sources](../usb/README.md) and [third-party notices](../THIRD_PARTY_NOTICES.md).
