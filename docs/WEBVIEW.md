# WebView and software emulators

Project preview uses Android's system `android.webkit.WebView`. Docker is optional: the preview screen is also tested against a local HTTP server without an engine or model login.

## Software TCG issue

Linux x86_64 Android Emulator **37.2.12-16428233** has a verified `helper_phaddd_xmm` defect. With `PHADDD xmm0, xmm0` and input `[1,2,3,4]`, it produced `[3,7,10,14]` instead of `[3,7,3,7]`: it overwrote part of the destination before reading all of the aliased source. The same operation ordering appears in [upstream QEMU code](https://android.googlesource.com/platform/external/qemu/+/ae9d18d2b6261179fbd57fffec720a04f7bfb053/target/i386/ops_sse.h).

Chromium 133 uses PHADDD for zlib Adler-32. Its SIMD path rejected valid WebView PNG data; an alternate decoder then failed on a valid V8 snapshot. Vulkan/PCLMUL/clock changes did not fix the instruction. For the examined16×16 icon, correct Adler-32 was `905e2606`, while defective SIMD returned `7ea7bdda`. The fixed instruction restores the default decode path.

## Guarded local workaround

[The launcher](../tools/start-software-emulator.sh) recognizes the exact verified engine by SHA256 and builds a separate cache copy. [The fixer](../tools/fix-software-emulator.py) replaces only the headless engine's `helper_phaddd_xmm`, loading both operands before storing. [Assembly](../tools/emulator/phaddd-fixed.S) and [source patch](../tools/emulator/phaddd-source.patch) are reviewable. The installed SDK engine, Android image and APK remain unchanged.

Only the known Linux x86_64 engine is supported. Full-file/function hashes are required; unknown versions and output directories are refused. SDK resources/license notices are preserved. This workaround is test infrastructure, not an app patch or phone modification, and does not certify other TCG instructions.

```sh
python3 tools/fix-software-emulator.py \
  --sdk-root "$ANDROID_SDK_ROOT" \
  --output "$HOME/.cache/androidagent/emulator-phaddd"
export ANDROIDAGENT_EMULATOR_ROOT="$HOME/.cache/androidagent/emulator-phaddd"
bash tools/start-software-emulator.sh androidagent_api36
```

Normally the launcher selects the cache automatically; the explicit output is optional. Use the regular SDK emulator with KVM. To reproduce the original defect, stop the emulator and select `ANDROIDAGENT_EMULATOR_ROOT="$ANDROID_SDK_ROOT/emulator"`. Unsetting that override restores automatic selection. An SDK update needs a fresh instruction test; unknown engines are launched without transplanting this patch.

## Tests

[The instruction probe](../tools/emulator/phaddd-probe.c) compares8192 vectors with independent arithmetic, including aliasing, separate registers and 32-bit overflow. It builds on the host or Android x86_64 through NDK, without root.

```sh
export ANDROID_SERIAL=emulator-5554
bash tools/test-webview.sh
```

The suite opens production `DockerPreviewActivity` and tests HTTP rendering, a JavaScript button, PNG/canvas pixels, storage across refresh, recreation, error/recovery and reopening. It checks actual painted bounds, not just view hierarchy presence. Use the same SDK/JDK/signing setup as [Building](BUILDING.md). Reports include run identity and provider version under `artifacts/api<API>/webview/`.

Full Docker and physical Lenovo suites separately test a real container's interactive preview. Completed runs and their scope are in [Testing](TESTING.md).
