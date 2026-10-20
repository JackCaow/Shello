#!/usr/bin/env python3
"""Start a loopback-only macOS OpenSSH fixture and provision the debug sample over USB."""
import argparse
import getpass
import os
from pathlib import Path
import shlex
import signal
import socket
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('action', choices=['start', 'stop'])
parser.add_argument('--serial', required=True)
parser.add_argument('--port', type=int, default=22222)
parser.add_argument('--adb', default=str(Path.home() / 'Library/Android/sdk/platform-tools/adb'))
args = parser.parse_args()
if not 1024 <= args.port <= 65535:
    parser.error('port must be between 1024 and 65535')
root = Path.home() / '.cache/ghostty-android-ssh-test'
root.mkdir(parents=True, exist_ok=True, mode=0o700)
os.chmod(root, 0o700)
package = 'dev.ghostty.android.sample'
base = [args.adb, '-s', args.serial]

def run(command, **kwargs):
    return subprocess.run(command, check=True, **kwargs)

def adb_shell(*command):
    return run(base + ['shell', shlex.join(command)], stdout=subprocess.DEVNULL)

def stop():
    # Validate the PID belongs to this fixture before signalling it.
    pid_file = root / 'sshd.pid'
    if pid_file.exists():
        pid = int(pid_file.read_text())
        result = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], capture_output=True, text=True)
        if str(root / 'sshd_config') in result.stdout:
            os.kill(pid, signal.SIGTERM)
        pid_file.unlink(missing_ok=True)
    subprocess.run(base + ['reverse', '--remove', f'tcp:{args.port}'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    adb_shell('am', 'force-stop', package)
    adb_shell('run-as', package, 'rm', '-rf', 'files/ssh')
    for name in ('client_key', 'client_key.pub', 'host_key', 'host_key.pub', 'authorized_keys', 'known_hosts'):
        (root / name).unlink(missing_ok=True)

if args.action == 'stop':
    stop()
    print('Stopped fixture, removed USB forwarding and test credentials.')
    raise SystemExit

# Do not replace a listener or rotate a running session's keys.
with socket.socket() as probe:
    if probe.connect_ex(('127.0.0.1', args.port)) == 0:
        raise SystemExit('Port is in use; stop the existing fixture first or select another port.')
for name in ('host_key', 'client_key'):
    (root / name).unlink(missing_ok=True)
    (root / (name + '.pub')).unlink(missing_ok=True)
    run(['ssh-keygen', '-q', '-t', 'ecdsa', '-b', '256', '-m', 'PEM', '-N', '', '-f', str(root / name)])
(root / 'authorized_keys').write_bytes((root / 'client_key.pub').read_bytes())
os.chmod(root / 'authorized_keys', 0o600)
public = (root / 'host_key.pub').read_text().split()
known_hosts = f'[127.0.0.1]:{args.port} {public[0]} {public[1]}\n'
(root / 'known_hosts').write_text(known_hosts)
work = root / 'workspace'
work.mkdir(exist_ok=True)
(work / 'unicode.txt').write_text('Ghostty Android / real SSH PTY\n' + ''.join(
    f'{i:03d} 中文输入 · 👨‍👩‍👧‍👦 · 👍🏽 · 🇨🇳 · 1️⃣ · é\n' for i in range(1, 241)))
(work / 'unicode-no-flag.txt').write_text((work / 'unicode.txt').read_text().replace('🇨🇳', 'CN'))
(work / 'ascii.txt').write_text(''.join(f'ASCII_{i:03d}\n' for i in range(1, 241)))
(root / 'shell.sh').write_text('#!/bin/bash\ncd ' + shlex.quote(str(work)) + '\n'
    'export LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8 HISTFILE=/dev/null BASH_SILENCE_DEPRECATION_WARNING=1\n'
    "export PS1='\\[\\033[32m\\]mac-local\\[\\033[0m\\] $ '\n"
    'exec /bin/bash --noprofile --norc -i\n')
config = f'''ListenAddress 127.0.0.1
Port {args.port}
HostKey "{root / 'host_key'}"
PidFile "{root / 'sshd.pid'}"
AuthorizedKeysFile "{root / 'authorized_keys'}"
AllowUsers {getpass.getuser()}
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM no
StrictModes yes
AllowTcpForwarding no
AllowAgentForwarding no
X11Forwarding no
PermitTunnel no
ForceCommand /bin/bash "{root / 'shell.sh'}"
'''
(root / 'sshd_config').write_text(config)
run(['/usr/sbin/sshd', '-t', '-f', str(root / 'sshd_config')])
with (root / 'server.log').open('w') as log:
    server = subprocess.Popen(['/usr/sbin/sshd', '-D', '-e', '-f', str(root / 'sshd_config')],
                              stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
(root / 'sshd.pid').write_text(str(server.pid))
try:
    for _ in range(50):
        if server.poll() is not None:
            raise RuntimeError('sshd exited; inspect the local server.log')
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', args.port)) == 0:
                break
        time.sleep(.1)
    else:
        raise RuntimeError('sshd did not start')
    adb_shell('run-as', package, 'mkdir', '-p', 'files/ssh')
    files = {'client_key': (root / 'client_key').read_bytes(),
             'known_hosts': known_hosts.encode(),
             'connection.properties': f'host=127.0.0.1\nport={args.port}\nuser={getpass.getuser()}\n'.encode()}
    for name, content in files.items():
        # A non-PTY shell streams bytes privately and also works with Android 8.1 adbd.
        run(base + ['shell', '-T', shlex.join(['run-as', package, 'tee', f'files/ssh/{name}'])],
            input=content, stdout=subprocess.DEVNULL)
        adb_shell('run-as', package, 'chmod', '600', f'files/ssh/{name}')
    run(base + ['reverse', f'tcp:{args.port}', f'tcp:{args.port}'], stdout=subprocess.DEVNULL)
    adb_shell('am', 'force-stop', package)
    adb_shell('am', 'start', '-n', package + '/.MainActivity', '--ez', 'connectLocalSsh', 'true')
except BaseException:
    stop()
    raise
print('Local SSH fixture ready; the sample is connecting through USB. Try vim unicode.txt or less unicode.txt.')
print('Stop with this script’s stop action when finished. No system SSH settings were changed.')
