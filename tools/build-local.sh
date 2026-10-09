#!/usr/bin/env bash
# Zero Maven dependencies: Android SDK build tools + JDK + Python 3.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_SDK_ROOT:?Set ANDROID_SDK_ROOT to your Android SDK}"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK}"
export PATH="$JAVA_HOME/bin:$ANDROID_SDK_ROOT/build-tools/35.0.0:$PATH"
export SDK_JAR="$ANDROID_SDK_ROOT/platforms/android-36/android.jar"
export KEYSTORE="${KEYSTORE:-$HOME/.androidagent-keys/development.jks}"
bash build.sh
mkdir -p artifacts
cp build/buddy-release.apk artifacts/androidagent-0.1.0.apk
