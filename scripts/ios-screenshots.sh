#!/usr/bin/env bash
# Captures every screen (UI test AllScreens) on a destination and exports the PNGs.
# usage: ios-screenshots.sh <name> <xcodebuild-destination> <sshd-dir> [sshd-host]   -> build/ios-screenshots/<name>/
#   sim:    ios-screenshots.sh iphone-sim 'platform=iOS Simulator,id=<udid>' /tmp/dir          (sshd on 127.0.0.1)
#   device: SSHD_LISTEN=<lan-ip> ios-screenshots.sh iphone-device 'id=<device-id>' /tmp/dir <lan-ip>  (needs DEVELOPMENT_TEAM)
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd); name=$1 dest=$2 dir=$3 host=${4:-127.0.0.1}
"$root/scripts/ios-test-sshd.sh" start "$dir/sshd" >/dev/null
trap '"$root/scripts/ios-test-sshd.sh" stop "$dir/sshd" || true' EXIT
export TEST_RUNNER_SHELLO_SSHD_USER=$(id -un) TEST_RUNNER_SHELLO_SSHD_HOST=$host TEST_RUNNER_SHELLO_SEED_KEY_B64=$(base64 < "$dir/sshd/user_ed25519" | tr -d '\n') TEST_RUNNER_SHELLO_SEED_HOSTPUB="$(cat "$dir/sshd/host_ed25519.pub")"
rm -rf "$dir/$name.xcresult"
extra=(); [[ -n ${DEVELOPMENT_TEAM:-} ]] && extra=(DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM" -allowProvisioningUpdates)
(cd "$root/ios" && xcodebuild test -project Shello.xcodeproj -scheme Shello -only-testing:ShelloUITests/AllScreens -destination "$dest" -derivedDataPath "$dir/dd-$name" -resultBundlePath "$dir/$name.xcresult" ${extra[@]+"${extra[@]}"} 2>&1 | grep -E "error:|Test Case.*(passed|failed)|TEST (SUCCEEDED|FAILED)" | cut -c1-260) || true
rm -rf "$root/build/ios-screenshots/$name"; "$root/scripts/export-ios-screenshots.sh" "$dir/$name.xcresult" "$root/build/ios-screenshots/$name"
