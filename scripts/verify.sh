#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
"$root/scripts/build-native.sh"
"$root/android/gradlew" -p "$root/android" --no-daemon :terminal:assembleRelease :terminal:lintRelease :sample:assembleDebug :sample:assembleRelease :sample:lintRelease :terminal:assembleDebugAndroidTest
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
 "$root/android/gradlew" -p "$root/android" --no-daemon :terminal:connectedDebugAndroidTest
else
 echo 'Device instrumentation not run: set ANDROID_SERIAL to an ARM64 device.'
fi
