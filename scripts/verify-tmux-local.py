#!/usr/bin/env python3
"""Exercise the managed tmux fixture using real OpenSSH PTYs and abrupt connection loss."""
import argparse
import fcntl
import getpass
import json
import os
from pathlib import Path
import pty
import select
import shlex
import struct
import subprocess
import sys
import termios
import time
import threading
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
root = Path.home() / '.cache/ghostty-android-ssh-test'
meta = json.loads((root / 'fixture.json').read_text())
assert meta['mode'] == 'tmux', 'Start local-ssh.py with --tmux first'
state = json.loads((root / 'tmux.json').read_text())

def pane_pid():
    return int(subprocess.check_output(['tmux', '-S', str(root / 'tmux.sock'), 'display-message',
        '-p', '-t', state['session'], '#{pane_pid}'], text=True).strip())

def wait_for(check, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.05)
    raise AssertionError('Timed out waiting for fixture state')

class Client:
    def __init__(self):
        self.fd, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', 24, 80, 0, 0))
        self.process = subprocess.Popen(['ssh', '-tt', '-p', str(meta['port']),
            '-i', str(root / 'client_key'), '-o', 'IdentitiesOnly=yes',
            '-o', 'StrictHostKeyChecking=yes', '-o', 'UserKnownHostsFile=' + str(root / 'known_hosts'),
            '-o', 'LogLevel=ERROR', f'{getpass.getuser()}@127.0.0.1'],
            stdin=slave, stdout=slave, stderr=slave, env={**os.environ, "TERM": "xterm-256color"})
        os.close(slave)
        self.output = b''
        self.reading = True
        self.reader = threading.Thread(target=self.read_output, daemon=True)
        self.reader.start()
        # tmux probes terminal capabilities during attachment. Drain the PTY throughout
        # startup and file assertions so SSH cannot block while flushing a full redraw.
        time.sleep(1)
    def read_output(self):
        while self.reading:
            if select.select([self.fd], [], [], .1)[0]:
                try:
                    data = os.read(self.fd, 65536)
                    if not data:
                        return
                    self.output += data
                except OSError:
                    return
    def send(self, text):
        os.write(self.fd, text.encode())
    def expect(self, text):
        wait_for(lambda: text.encode() in self.output)
    def disconnected(self):
        wait_for(lambda: self.process.poll() is not None)
    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
        try:
            self.process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.process.kill(); self.process.wait(timeout=5)
        self.reading = False
        self.reader.join(timeout=1)
        os.close(self.fd)

def drop():
    subprocess.run([sys.executable, str(Path(__file__).with_name('local-ssh.py')), 'drop',
                    '--serial', 'host-only'], check=True, stdout=subprocess.DEVNULL)

shell_pid = pane_pid()
token = 'RESUME_' + uuid.uuid4().hex[:8]
work = root / 'workspace' / token
client = Client()
try:
    client.expect('mac-local')
    client.send(f'mkdir -p {shlex.quote(str(work))}; cd {shlex.quote(str(work))}; export RESUME_TOKEN={token}; echo $$ > shell.pid\r')
    wait_for(lambda: (work / 'shell.pid').exists() and (work / 'shell.pid').read_text().strip().isdigit())
    assert int((work / 'shell.pid').read_text()) == shell_pid
    drop()
    client.disconnected()
finally:
    client.close()
client = Client()
try:
    client.expect('mac-local')
    client.send("printf '%s\\n' \"$$\" \"$RESUME_TOKEN\" \"${PWD##*/}\" > shell-restored.txt\r")
    wait_for(lambda: (work / 'shell-restored.txt').exists() and len((work / 'shell-restored.txt').read_text().splitlines()) == 3)
    assert (work / 'shell-restored.txt').read_text().splitlines() == [str(shell_pid), token, token]
    client.send("vim -Nu NONE -n -c 'call writefile([string(getpid())], \"vim.pid\")' draft.txt\r")
    wait_for(lambda: (work / 'vim.pid').exists() and (work / 'vim.pid').read_text().strip().isdigit())
    vim_pid = int((work / 'vim.pid').read_text())
    client.expect('draft.txt')
    client.send('iUNSAVED_' + token)
    client.expect('UNSAVED_' + token)
    assert not (work / 'draft.txt').exists(), 'Vim buffer must still be unsaved'
    drop()
    client.disconnected()
    os.kill(vim_pid, 0)
    assert pane_pid() == shell_pid
finally:
    client.close()
client = Client()
try:
    client.expect('UNSAVED_' + token)
    os.kill(vim_pid, 0)
    client.send('\033:wq\r')
    wait_for(lambda: (work / 'draft.txt').exists() and (work / 'draft.txt').read_text().endswith('\n'))
    assert (work / 'draft.txt').read_text() == 'UNSAVED_' + token + '\n'
    client.expect('mac-local')
    client.send("printf '%s\\n' \"$$\" \"$RESUME_TOKEN\" \"${PWD##*/}\" > after-vim.txt\r")
    wait_for(lambda: (work / 'after-vim.txt').exists() and len((work / 'after-vim.txt').read_text().splitlines()) == 3)
    assert (work / 'after-vim.txt').read_text().splitlines() == [str(shell_pid), token, token]
    client.send('cd ..\r')
finally:
    client.close()
result = {'shell_pid_before': shell_pid, 'shell_pid_after': pane_pid(), 'vim_pid_before': vim_pid,
          'vim_pid_after_reconnect': vim_pid, 'abrupt_ssh_drops': 2,
          'shell_variable_and_cwd_preserved': True, 'unsaved_buffer_redrawn_after_reconnect': True,
          'saved_file_exact_match': True, 'tmux': subprocess.check_output(['tmux','-V'], text=True).strip()}
args.output.parent.mkdir(parents=True, exist_ok=True)
args.output.write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result))
