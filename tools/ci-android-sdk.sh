#!/usr/bin/env bash
# GitHub-hosted runners no longer put the Android command-line tools on PATH (and may not
# ship them). Ensure sdkmanager/avdmanager/adb/emulator exist and export them for later steps.
set -euo pipefail
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}"
if [ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]; then
  work=$(mktemp -d)
  curl -fsSL -o "$work/cmdline.zip" https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
  unzip -q "$work/cmdline.zip" -d "$work"
  sudo mkdir -p "$SDK/cmdline-tools"
  sudo rm -rf "$SDK/cmdline-tools/latest"
  sudo mv "$work/cmdline-tools" "$SDK/cmdline-tools/latest"
  sudo chown -R "$(id -u):$(id -g)" "$SDK/cmdline-tools"
fi
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$PATH"
if [ -n "${GITHUB_PATH:-}" ]; then
  { echo "$SDK/cmdline-tools/latest/bin"; echo "$SDK/platform-tools"; echo "$SDK/emulator"; } >> "$GITHUB_PATH"
  echo "ANDROID_SDK_ROOT=$SDK" >> "$GITHUB_ENV"
fi
yes | sdkmanager --licenses >/dev/null 2>&1 || true
[ $# -gt 0 ] && sdkmanager "$@" >/dev/null
sdkmanager --version
