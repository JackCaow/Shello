#!/usr/bin/env bash
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd)
"$root/gradlew" -p "$root" --no-daemon :terminal:publishReleasePublicationToMavenLocal
"$root/gradlew" -p "$root/tests/consumer" --no-daemon :app:assembleRelease
