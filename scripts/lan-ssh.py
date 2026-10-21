#!/usr/bin/env python3
"""Expose the existing local SSH/tmux fixture on one explicit LAN IPv4 address.

Keeps its keys, host trust, ForceCommand and remote processes. No adb reverse is used.
Run stop to remove this additional listener; local-ssh.py still owns the fixture.
"""
import argparse
import getpass
import ipaddress
import json
import os
from pathlib import Path
import shlex
import signal
import socket
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('action', choices=['connect', 'stop'])
parser.add_argument('--address', help='A private IPv4 address assigned to this Mac (not 0.0.0.0)')
parser.add_argument('--serial')
parser.add_argument('--adb', default=str(Path.home() / 'Library/Android/sdk/platform-tools/adb'))
args = parser.parse_args()
root = Path.home() / '.cache/ghostty-android-ssh-test'
config_file = root / 'lan-sshd_config'
pid_file = root / 'lan-sshd.pid'

def listener():
    if not pid_file.exists():
        return None
    pid = int(pid_file.read_text())
    command = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], capture_output=True, text=True).stdout
    return pid if str(config_file) in command else None

if args.action == 'stop':
    pid = listener()
    if pid:
        os.kill(pid, signal.SIGTERM)
    pid_file.unlink(missing_ok=True)
    print('LAN listener stopped. Existing SSH children, keys and tmux are not destroyed.')
    raise SystemExit

if not args.serial or not args.address:
    parser.error('connect requires --serial and --address')
try:
    address = ipaddress.IPv4Address(args.address)
except ipaddress.AddressValueError:
    parser.error('address must be a private LAN IPv4 literal')
if not address.is_private or address.is_loopback or address.is_unspecified or address.is_link_local or address.is_multicast:
    parser.error('address must be a private LAN IPv4 address')
metadata = json.loads((root / 'fixture.json').read_text())
if metadata['mode'] != 'tmux':
    parser.error('prepare an existing tmux fixture first with local-ssh.py start --tmux --host-only')
port = metadata['port']
config = (root / 'sshd_config').read_text().replace('ListenAddress 127.0.0.1', 'ListenAddress ' + str(address))
config = config.replace(str(root / 'sshd.pid'), str(pid_file))
if listener():
    if config_file.read_text() != config:
        parser.error('a different LAN listener is running; stop it first')
else:
    # Binding verifies that this specific address belongs to the host and the port is free.
    with socket.socket() as probe:
        probe.bind((str(address), port))
    config_file.write_text(config)
    subprocess.run(['/usr/sbin/sshd', '-t', '-f', str(config_file)], check=True)
    with (root / 'lan-server.log').open('w') as log:
        server = subprocess.Popen(['/usr/sbin/sshd', '-D', '-e', '-f', str(config_file)],
                                  stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
    pid_file.write_text(str(server.pid))
    for _ in range(50):
        if server.poll() is not None:
            raise SystemExit('LAN listener exited; inspect private lan-server.log')
        with socket.socket() as probe:
            probe.settimeout(.2)
            if probe.connect_ex((str(address), port)) == 0:
                break
        time.sleep(.1)
    else:
        server.terminate()
        raise SystemExit('LAN listener did not become ready')

base = [args.adb, '-s', args.serial]
pkg = 'dev.ghostty.android.sample'
def shell(*command):
    subprocess.run(base + ['shell', shlex.join(command)], check=True, stdout=subprocess.DEVNULL)
public = (root / 'host_key.pub').read_text().split()
files = {
    'client_key': (root / 'client_key').read_bytes(),
    'known_hosts': f'[{address}]:{port} {public[0]} {public[1]}\n'.encode(),
    'connection.properties': f'host={address}\nport={port}\nuser={getpass.getuser()}\nsessionMode=tmux\n'.encode(),
}
shell('am', 'force-stop', pkg)
shell('run-as', pkg, 'mkdir', '-p', 'files/ssh')
for name, content in files.items():
    subprocess.run(base + ['shell', '-T', shlex.join(['run-as', pkg, 'tee', f'files/ssh/{name}'])],
                   input=content, check=True, stdout=subprocess.DEVNULL)
    shell('run-as', pkg, 'chmod', '600', f'files/ssh/{name}')
# Remove only this fixture's old USB mapping, not other developer mappings.
subprocess.run(base + ['reverse', '--remove', f'tcp:{port}'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
shell('am', 'start', '-n', pkg + '/.MainActivity', '--ez', 'connectLocalSsh', 'true')
print('Sample provisioned for direct LAN SSH with pinned host key; original tmux retained.')
print('Stop this extra listener with scripts/lan-ssh.py stop when finished.')
