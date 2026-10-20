#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
"$root/scripts/build-native.sh"
"$root/gradlew" -p "$root" --no-daemon :terminal:assembleRelease :terminal:lintRelease :sample:assembleDebug :sample:assembleRelease :terminal:assembleDebugAndroidTest
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
 "$root/gradlew" -p "$root" --no-daemon :terminal:connectedDebugAndroidTest
else
 echo 'Device instrumentation not run: set ANDROID_SERIAL to an ARM64 device.'
fi
