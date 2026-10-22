#!/usr/bin/env bash
# Full iOS acceptance run on the iOS Simulator: native build check, localization, package tests,
# hosted app tests and the end-to-end UI flows against a local sshd and companion/pair.py.
# usage: scripts/verify-ios.sh [simulator-udid]    (needs Xcode, xcodegen; builds natives first if missing)
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd); cd "$root"
sim=${1:-$(xcrun simctl list devices booted | grep -Eo '[0-9A-F-]{36}' | head -1)}
[[ -n $sim ]] || { echo 'Boot an iOS simulator first (xcrun simctl boot ...)' >&2; exit 1; }
dest="platform=iOS Simulator,id=$sim"
work=${SHELLO_VERIFY_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/shello-verify.XXXXXX")}
mkdir -p "$work"
step() { printf '\n== %s\n' "$*"; }
[[ -d ios/Frameworks/ShelloNative.xcframework ]] || { step 'building native libraries'; scripts/build-native-ios.sh; scripts/build-ssh-ios.sh; scripts/assemble-ios-frameworks.sh; }
step 'localization'; python3 scripts/check-ios-localization.py
[[ -x .native/pair-venv/bin/python3 ]] || { python3 -m venv .native/pair-venv && .native/pair-venv/bin/pip install -q qrcode; }
trap '[[ -f "$work/sshd/passwd.pid" ]] && kill "$(cat "$work/sshd/passwd.pid")" 2>/dev/null; "$root/scripts/ios-test-pair.sh" stop "$work/sshd" || true; "$root/scripts/ios-test-sshd.sh" stop "$work/sshd" || true' EXIT
scripts/ios-test-sshd.sh start "$work/sshd" >/dev/null
.native/pair-venv/bin/python3 -c 'import paramiko' 2>/dev/null || .native/pair-venv/bin/pip install -q paramiko
(nohup .native/pair-venv/bin/python3 scripts/ios-test-passwd-server.py 2398 tester s3cret "$work/sshd" >"$work/sshd/passwd.log" 2>&1 &)
for _ in $(seq 20); do [[ -s "$work/sshd/passwd_host.pub" ]] && break; sleep 0.25; done; scripts/ios-test-pair.sh start "$work/sshd"
ipad=${SHELLO_IPAD_UDID:-$(xcrun simctl list devices available | grep -E 'iPad' | grep -Eo '[0-9A-F-]{36}' | head -1 || true)}
[[ ${SHELLO_SKIP_IPAD:-0} == 1 ]] && ipad=''
export TEST_RUNNER_SHELLO_SSHD_DIR="$work/sshd" TEST_RUNNER_SHELLO_SSHD_USER=$(id -un) TEST_RUNNER_SHELLO_SSHD_PORT=2299
seed_env() { # UI tests get secrets through the environment so they also work on a physical device
 export TEST_RUNNER_SHELLO_SEED_KEY_B64=$(base64 < "$work/sshd/user_ed25519" | tr -d '\n') TEST_RUNNER_SHELLO_SEED_HOSTPUB=$(cat "$work/sshd/host_ed25519.pub")
 [[ -s "$work/sshd/qr.txt" ]] && export TEST_RUNNER_SHELLO_QR=$(tr -d '\n' < "$work/sshd/qr.txt") || true
}
export TEST_RUNNER_SHELLO_HOOK_SCRIPT="$root/public/agent/ghostty_hook.py" TEST_RUNNER_SHELLO_HOOK_STATE="$work/hook"; mkdir -p "$work/hook"
cd ios; xcodegen generate >/dev/null
step 'package tests (engine, core, ssh, terminal view, services)'
xcodebuild test -workspace . -scheme ShelloKit-Package -destination "$dest" -parallel-testing-enabled NO -resultBundlePath "$work/package.xcresult" 2>&1 | tee "$work/package.log" | grep -E "error:|Test Case.*(passed|failed|skipped)|Executed [0-9]+ tests|Restarting after|Failing tests:|TEST (SUCCEEDED|FAILED)"
grep -q 'TEST SUCCEEDED' "$work/package.log"
scripts_dir=$root/scripts
critical=(ShelloUITests/ShelloUITests/testPairingEndToEndThenShell ShelloUITests/ShelloUITests/testSavedConnectionOpensShellAndRunsCommands ShelloUITests/UIReviewTests/testRootEdgeSwipeDoesNotBlockOpeningAnotherPage ShelloUITests/UIReviewTests/testHomeSessionLongPressReorderCloseAndCloseAll)
run_app_tests() {
 local app_dest=$1 prefix=$2 models=$3 test
 local -a smoke remaining
 step "$prefix: critical UI flows (fresh pairing ticket, SSH state, root edge-back and session reorder)"
 "$scripts_dir/ios-test-pair.sh" stop "$work/sshd" || true
 "$scripts_dir/ios-test-pair.sh" start "$work/sshd"; seed_env
 if [[ -z ${SHELLO_ONLY:-} ]]; then
  smoke=()
  [[ $models == 1 ]] && smoke+=(-only-testing:ShelloAppTests)
  for test in "${critical[@]}"; do smoke+=("-only-testing:$test"); done
  # The ticket expires after 10 minutes. Run its flow before the lengthy screenshot walkthroughs.
  xcodebuild test -project Shello.xcodeproj -scheme Shello "${smoke[@]}" -destination "$app_dest" -parallel-testing-enabled NO -resultBundlePath "$prefix-smoke.xcresult" 2>&1 | tee "$prefix-smoke.log" | grep -E "error:|Test Case.*(passed|failed|skipped)|Executed [0-9]+ tests|TEST (SUCCEEDED|FAILED)"
  grep -q 'TEST SUCCEEDED' "$prefix-smoke.log"
 fi
 step "$prefix: remaining UI flows"
 remaining=()
 if [[ -n ${SHELLO_ONLY:-} ]]; then remaining+=("-only-testing:$SHELLO_ONLY"); else
  remaining+=(-skip-testing:ShelloAppTests)
  for test in "${critical[@]}"; do remaining+=("-skip-testing:$test"); done
 fi
 xcodebuild test -project Shello.xcodeproj -scheme Shello "${remaining[@]}" -destination "$app_dest" -parallel-testing-enabled NO -resultBundlePath "$prefix.xcresult" 2>&1 | tee "$prefix.log" | grep -E "error:|Test Case.*(passed|failed|skipped)|Executed [0-9]+ tests|TEST (SUCCEEDED|FAILED)"
 grep -q 'TEST SUCCEEDED' "$prefix.log"
}
run_app_tests "$dest" "$work/app" 1
if [[ -n $ipad ]]; then
 step "UI flows on iPad ($ipad)"; xcrun simctl boot "$ipad" 2>/dev/null || true
 run_app_tests "platform=iOS Simulator,id=$ipad" "$work/ipad" 0
fi
step 'release build for devices (unsigned)'
xcodebuild -project Shello.xcodeproj -scheme Shello -configuration Release -destination 'generic/platform=iOS' CODE_SIGNING_ALLOWED=NO -derivedDataPath "$work/dd" build 2>&1 | tee "$work/release.log" | grep -E "error:|BUILD" | tail -2
echo; echo 'iOS verification passed'
