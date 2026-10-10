# USB runtime sources

Pinned origin commits: `sources.json`. File hashes after documented Android adaptation: `source-manifest.json`. Rebuild JNI with `tools/build-usb-native.sh` after preparing QEMU headers and libraries. Android NDK 29.0.14206865, API 30, ARM64/x86_64, 16 KiB alignment.

- `usb-serial-for-android` 3.11.0 Java sources are under `app/src/main/java/com/hoho/android/usbserial`. Adaptations: remove AndroidX IntDef import/annotation; add BuildConfig DEBUG=false. MIT license and upstream copyright notices preserved.
- `native/vendor/esp`: Espressif source, Apache-2.0. Our link includes serial loader/protocol/MD5; no eFuse API is linked or exposed. `native/esp_jni.c` is the Android I/O port.
- `native/bridge.c`: app-private Unix socket usbredir host around the Android-granted libusb descriptor. `libusbredirhost.so` and its dependency closure are generated from pinned Termux packages; origin and LGPL source/notice locations are in `docker/qemu-lock.json`, `docker/qemu-libraries-*.json` and `docker/licenses`.
- `native-manifest.json`: generated Android shared-library hashes checked by both build paths. Binaries are ignored in Git and supplied in the full APK.

Build diagnostics and physical hardware limitations are described in `docs/USB.md`. No hardware profile is claimed validated by the emulator alone.
