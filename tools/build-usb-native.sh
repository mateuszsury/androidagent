#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_NDK_ROOT:?Set ANDROID_NDK_ROOT to Android NDK r29}"
CLANG="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64/bin"
SRC=usb/native/vendor/esp
mkdir -p build-runtime/usb-native
for ABI in arm64-v8a x86_64; do
  case "$ABI" in arm64-v8a) TARGET=aarch64-linux-android30;; x86_64) TARGET=x86_64-linux-android30;; esac
  HEADERS="build-runtime/qemu/$ABI/root/data/data/com.termux/files/usr/include"
  NATIVE="app/src/main/jniLibs/$ABI"
  "$CLANG/$TARGET-clang" -std=c11 -D_GNU_SOURCE -O2 -fPIC -shared -fvisibility=hidden -ffunction-sections -fdata-sections -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,-rpath,'$ORIGIN' -Wl,--no-undefined \
    -I"$HEADERS" -I"$HEADERS/libusb-1.0" -I"$SRC/include" -I"$SRC/src" -I"$SRC/private_include" \
    usb/native/bridge.c usb/native/esp_jni.c "$SRC"/src/md5_hash.c "$SRC"/src/esp_loader.c "$SRC"/src/esp_targets.c \
    "$SRC"/src/protocol_serial.c "$SRC"/src/protocol_uart.c "$SRC"/src/protocol_spi.c "$SRC"/src/protocol_sdio.c "$SRC"/src/slip.c \
    -L"$NATIVE" -lusb-1.0 -lusbredirhost -lusbredirparser -llog -o "$NATIVE/libaausb.so"
done
python3 - <<'PY'
import json,hashlib,pathlib
report={}
for abi in ['arm64-v8a','x86_64']:
 p=pathlib.Path('app/src/main/jniLibs')/abi/'libaausb.so'
 report[abi]=hashlib.sha256(p.read_bytes()).hexdigest()
pathlib.Path('usb/native-manifest.json').write_text(json.dumps(report,indent=2)+'\n')
PY
echo 'USB JNI runtimes built for ARM64 and x86_64'
