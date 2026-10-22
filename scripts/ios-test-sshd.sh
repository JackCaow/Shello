#!/usr/bin/env bash
# Starts an unprivileged sshd on 127.0.0.1 for the iOS integration tests.
# usage: ios-test-sshd.sh start <dir> | stop <dir>   (prints the port on start)
set -euo pipefail
cmd=${1:?start|stop}; dir=${2:?directory}
case $cmd in
start)
 mkdir -p "$dir" && chmod 700 "$dir" && cd "$dir"
 [[ -f host_ed25519 ]] || ssh-keygen -q -t ed25519 -N '' -f host_ed25519
 [[ -f host_rsa ]] || ssh-keygen -q -t rsa -b 3072 -N '' -f host_rsa
 [[ -f user_ed25519 ]] || ssh-keygen -q -t ed25519 -N '' -f user_ed25519
 [[ -f user_rsa ]] || ssh-keygen -q -t rsa -b 3072 -N '' -f user_rsa -m PEM
 [[ -f user_enc ]] || ssh-keygen -q -t ed25519 -N 'secret pass' -f user_enc
 cat user_ed25519.pub user_rsa.pub user_enc.pub > authorized_keys; chmod 600 authorized_keys
 port=2299
 tmuxdir=$HOME/.cache/shello-test-tmux   # short path: unix socket paths are limited to ~100 bytes
 mkdir -p "$tmuxdir" && chmod 700 "$tmuxdir"   # tests use their own tmux server, never the user's
 cat > sshd_config <<CFG
Port $port
ListenAddress ${SSHD_LISTEN:-127.0.0.1}
HostKey $dir/host_ed25519
HostKey $dir/host_rsa
AuthorizedKeysFile $dir/authorized_keys
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
UsePAM no
StrictModes no
PidFile $dir/sshd.pid
LogLevel ERROR
PerSourcePenalties no
AcceptEnv LANG LC_*
SetEnv TMUX_TMPDIR=$tmuxdir
MaxStartups 100:30:200
Subsystem sftp /usr/libexec/sftp-server
CFG
 if [[ -f sshd.pid ]] && kill -0 "$(cat sshd.pid)" 2>/dev/null; then echo $port; exit 0; fi
 if nc -z ${SSHD_LISTEN:-127.0.0.1} $port 2>/dev/null; then echo "port $port is already in use by another sshd (stop it with: $0 stop <its dir>)" >&2; exit 1; fi
 /usr/sbin/sshd -f "$dir/sshd_config" -E "$dir/sshd.log"
 for _ in $(seq 20); do nc -z ${SSHD_LISTEN:-127.0.0.1} $port 2>/dev/null && { echo $port; exit 0; }; sleep 0.2; done
 echo "sshd did not start" >&2; cat "$dir/sshd.log" >&2; exit 1 ;;
stop) [[ -f "$dir/sshd.pid" ]] && kill "$(cat "$dir/sshd.pid")" 2>/dev/null || true ;;
esac
