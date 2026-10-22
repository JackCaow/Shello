#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
"$root/android/gradlew" -p "$root/android" --no-daemon :terminal:publishReleasePublicationToMavenLocal
"$root/android/gradlew" -p "$root/android/tests/consumer" --no-daemon :app:assembleRelease
