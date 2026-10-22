#!/usr/bin/env bash
# Starts companion/pair.py against the ios-test-sshd for an end-to-end pairing test.
# usage: ios-test-pair.sh start <sshd-dir> | stop <sshd-dir>; the QR payload lands in <sshd-dir>/qr.txt
set -euo pipefail
root=$(cd "$(dirname "$0")/.." && pwd); cmd=${1:?}; dir=${2:?}
case $cmd in
start)
 startup_seconds=${SHELLO_PAIR_START_TIMEOUT:-90}
 [[ $startup_seconds =~ ^[0-9]+$ ]] && ((startup_seconds >= 1 && startup_seconds <= 120)) || { echo 'Pairing startup timeout must be 1..120 seconds' >&2; exit 1; }
 rm -f "$dir/qr.txt"
 nohup "${PAIR_PYTHON:-$root/.native/pair-venv/bin/python3}" -u "$root/companion/pair.py" --host "${PAIR_HOST:-127.0.0.1}" --ssh-port 2299 --host-key "$dir/host_ed25519.pub" --authorized-keys "$dir/authorized_keys" \
  --qr-file "$dir/qr.txt" --ttl 600 --user "$(id -un)" --name "Test Mac" >"$dir/pair.log" 2>&1 &
 pair_pid=$!; echo "$pair_pid" > "$dir/pair.pid"
 for _ in $(seq $((startup_seconds * 4))); do
  [[ -s "$dir/qr.txt" ]] && exit 0
  kill -0 "$pair_pid" 2>/dev/null || break
  sleep 0.25
 done
 kill "$pair_pid" 2>/dev/null || true; wait "$pair_pid" 2>/dev/null || true
 rm -f "$dir/pair.pid"
 echo "Pairing fixture did not become ready within $startup_seconds seconds" >&2
 cat "$dir/pair.log" >&2; exit 1 ;;
stop) [[ -f "$dir/pair.pid" ]] && kill "$(cat "$dir/pair.pid")" 2>/dev/null || true ;;
esac
