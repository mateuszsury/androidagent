#!/usr/bin/env bash
# For hosts without /dev/kvm. Hardware acceleration is faster when available.
set -euo pipefail
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT}"
: "${1:?Pass an existing AVD name}"
# The exact affected Linux build gets an isolated TCG fix automatically. An
# explicit directory overrides this selection (including for stock repros).
EMULATOR_ROOT="${ANDROIDAGENT_EMULATOR_ROOT:-$ANDROID_SDK_ROOT/emulator}"
ENGINE="$ANDROID_SDK_ROOT/emulator/qemu/linux-x86_64/qemu-system-x86_64-headless"
if [ -z "${ANDROIDAGENT_EMULATOR_ROOT:-}" ] && [ "$(uname -s)" = Linux ] && [ "$(uname -m)" = x86_64 ] && [ -f "$ENGINE" ]; then
  ENGINE_SHA=$(sha256sum "$ENGINE" | cut -d ' ' -f 1)
  if [ "$ENGINE_SHA" = 23b788a3b87b3228558924de4cb5477e2818b3b9415d6ce27d6438cf3e462b98 ]; then
    EMULATOR_ROOT="${XDG_CACHE_HOME:-$HOME/.cache}/androidagent/emulator-37.2.12-phaddd"
    python3 "$(dirname "$0")/fix-software-emulator.py" --sdk-root "$ANDROID_SDK_ROOT" --output "$EMULATOR_ROOT" >/dev/null
    echo "Using isolated Android Emulator with the verified TCG PHADDD fix: $EMULATOR_ROOT" >&2
  fi
fi
exec "$EMULATOR_ROOT/emulator" -avd "$1" -no-window -no-audio -no-boot-anim -no-snapshot -gpu swiftshader_indirect -accel off -cores 2 -memory 4096 -camera-back none -camera-front none -qemu -accel tcg,thread=single -icount shift=0,align=off,sleep=on -tb-size 1024
