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
import json
import shutil
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('action', choices=['start', 'connect', 'stop', 'drop', 'status'])
parser.add_argument('--serial', required=True)
parser.add_argument('--host-only', action='store_true', help='Prepare or stop the Mac fixture without touching Android')
parser.add_argument('--tmux', action='store_true', help='Create a dedicated persistent tmux session; reconnect attaches only')
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

def provision(mode, port):
    adb_shell('run-as', package, 'mkdir', '-p', 'files/ssh')
    files = {'client_key': (root / 'client_key').read_bytes(),
             'known_hosts': (root / 'known_hosts').read_bytes(),
             'connection.properties': f'host=127.0.0.1\nport={port}\nuser={getpass.getuser()}\nsessionMode={mode}\n'.encode()}
    for name, content in files.items():
        # A non-PTY shell streams bytes privately and also works with Android 8.1 adbd.
        run(base + ['shell', '-T', shlex.join(['run-as', package, 'tee', f'files/ssh/{name}'])],
            input=content, stdout=subprocess.DEVNULL)
        adb_shell('run-as', package, 'chmod', '600', f'files/ssh/{name}')
    run(base + ['reverse', f'tcp:{port}', f'tcp:{port}'], stdout=subprocess.DEVNULL)
    adb_shell('am', 'force-stop', package)
    adb_shell('am', 'start', '-n', package + '/.MainActivity', '--ez', 'connectLocalSsh', 'true')

def fixture_listener():
    pid_file = root / 'sshd.pid'
    if not pid_file.exists():
        return None
    pid = int(pid_file.read_text())
    result = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], capture_output=True, text=True)
    return pid if str(root / 'sshd_config') in result.stdout else None

def tmux_command(*command, check=True):
    binary = shutil.which('tmux')
    if not binary:
        raise SystemExit('tmux is required for this fixture mode')
    return subprocess.run([binary, '-S', str(root / 'tmux.sock'), *command], check=check,
                          capture_output=True, text=True)

if args.action == 'connect':
    if fixture_listener() is None:
        raise SystemExit('Start the fixture first; connect will not create a replacement session.')
    metadata = json.loads((root / 'fixture.json').read_text())
    provision(metadata['mode'], metadata['port'])
    print('Provisioned Android and opened the existing fixture connection.')
    raise SystemExit

if args.action == 'drop':
    listener = fixture_listener()
    if listener is None:
        raise SystemExit('No matching fixture SSH listener')
    # Abruptly terminate only this listener's connection handlers, not tmux or its panes.
    processes = subprocess.check_output(['ps', '-axo', 'pid=,ppid=,comm='], text=True)
    records = [line.strip().split(None, 2) for line in processes.splitlines()]
    descendants = {listener}
    targets = []
    while True:
        found = [(int(pid), command) for pid, parent, command in records
                 if int(parent) in descendants and int(pid) not in descendants]
        if not found:
            break
        for pid, command in found:
            descendants.add(pid)
            if 'sshd' in command:
                targets.append(pid)
    count = 0
    # Kill connection workers before their monitors; killing only a monitor can leave a live socket.
    for pid in reversed(targets):
        try:
            os.kill(pid, signal.SIGKILL)
            count += 1
        except ProcessLookupError:
            pass
    print(f'Dropped {count} SSH connection handler(s); listener and tmux were left running.')
    raise SystemExit

if args.action == 'status':
    state_file = root / 'tmux.json'
    state = json.loads(state_file.read_text()) if state_file.exists() else {'mode': 'plain'}
    state['ssh_listener_running'] = fixture_listener() is not None
    if state.get('session'):
        result = tmux_command('list-panes', '-t', state['session'], '-F', '#{pane_id} #{pane_pid} #{pane_current_command} #{pane_dead}', check=False)
        state['panes'] = result.stdout.strip().splitlines()
    print(json.dumps(state, ensure_ascii=False))
    raise SystemExit

def stop():
    # Validate the PID belongs to this fixture before signalling it.
    pid_file = root / 'sshd.pid'
    if pid_file.exists():
        pid = int(pid_file.read_text())
        result = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], capture_output=True, text=True)
        if str(root / 'sshd_config') in result.stdout:
            os.kill(pid, signal.SIGTERM)
        pid_file.unlink(missing_ok=True)
    if not args.host_only:
        subprocess.run(base + ['reverse', '--remove', f'tcp:{args.port}'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        # A disconnected device must not prevent cleanup of the host listener, tmux or keys.
        for command in (['am', 'force-stop', package], ['run-as', package, 'rm', '-rf', 'files/ssh']):
            result = subprocess.run(base + ['shell', shlex.join(command)], capture_output=True)
            if result.returncode:
                print('Android cleanup unavailable; reconnect the device and run stop again.')
    if (root / 'tmux.json').exists():
        tmux_command('kill-server', check=False)
        (root / 'tmux.json').unlink(missing_ok=True)
    for name in ('client_key', 'client_key.pub', 'host_key', 'host_key.pub', 'authorized_keys', 'known_hosts'):
        (root / name).unlink(missing_ok=True)

if args.action == 'stop':
    stop()
    print('Stopped host fixture (including its dedicated tmux processes) and removed generated host-side credentials.')
    if args.host_only:
        print('Android profile/USB mapping untouched (--host-only).')
    raise SystemExit

if args.tmux and not shutil.which('tmux'):
    raise SystemExit('Install tmux on the Mac before using --tmux.')
if fixture_listener() is not None or (root / 'tmux.json').exists():
    raise SystemExit('A managed fixture already exists; use connect to attach, or stop before creating another.')

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
try:
    session_name = None
    entrypoint = root / 'shell.sh'
    if args.tmux:
        session_name = 'mobile-' + uuid.uuid4().hex[:12]
        # A separate socket/config ensures this cannot attach to or stop the user's other tmux sessions.
        (root / 'tmux.conf').write_text('set -g default-terminal xterm-256color\n'
            'set -g status off\nset -g history-limit 5000\nset -g exit-empty off\n'
            'set -g destroy-unattached off\nset -g mouse off\nset -sg escape-time 10\n')
        tmux_command('-f', str(root / 'tmux.conf'), 'new-session', '-d', '-s', session_name,
                     '-x', '80', '-y', '24', '-c', str(work), '/bin/bash ' + shlex.quote(str(root / 'shell.sh')))
        (root / 'tmux.json').write_text(json.dumps({'mode': 'tmux', 'session': session_name}))
        entrypoint = root / 'attach.sh'
        entrypoint.write_text('#!/bin/bash\nexport LANG=en_US.UTF-8 LC_ALL=en_US.UTF-8\nexec '
            + shlex.join([shutil.which('tmux'), '-u', '-S', str(root / 'tmux.sock'),
                          'attach-session', '-d', '-t', session_name]) + '\n')
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
    ForceCommand /bin/bash "{entrypoint}"
    '''
    (root / 'sshd_config').write_text(config)
    (root / 'fixture.json').write_text(json.dumps({'port': args.port, 'mode': 'tmux' if args.tmux else 'plain'}))
    run(['/usr/sbin/sshd', '-t', '-f', str(root / 'sshd_config')])
    with (root / 'server.log').open('w') as log:
        server = subprocess.Popen(['/usr/sbin/sshd', '-D', '-e', '-f', str(root / 'sshd_config')],
                                  stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
    (root / 'sshd.pid').write_text(str(server.pid))
    for _ in range(50):
        if server.poll() is not None:
            raise RuntimeError('sshd exited; inspect the local server.log')
        with socket.socket() as probe:
            if probe.connect_ex(('127.0.0.1', args.port)) == 0:
                break
        time.sleep(.1)
    else:
        raise RuntimeError('sshd did not start')
    if not args.host_only:
        provision('tmux' if args.tmux else 'plain', args.port)
except BaseException:
    stop()
    raise
print('Local SSH fixture ready.' if args.host_only else 'Local SSH fixture ready; the sample is connecting through USB.')
if args.tmux:
    print('SSH reconnect attaches the same tmux shell. drop breaks only the SSH connection; stop ends the test session.')
print('Stop with this script’s stop action when finished. No system SSH settings were changed.')
