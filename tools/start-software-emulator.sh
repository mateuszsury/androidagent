#!/usr/bin/env bash
# For hosts without /dev/kvm. Hardware acceleration is faster when available.
set -euo pipefail
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
: "${1:?Pass an existing AVD name}"
exec "$ANDROID_SDK_ROOT/emulator/emulator" -avd "$1" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel off -cores 2 -memory 4096 -camera-back none -camera-front none -qemu -accel tcg,thread=single -icount shift=0,align=off,sleep=on -tb-size 1024
