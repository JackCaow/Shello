"""Pairing skill checks; package/service commands are mocked, keys and sockets isolated."""
import hashlib
import http.client
import importlib.util
import io
import json
import os
from pathlib import Path
import queue
import shutil
import signal
import socket
import ssl
import struct
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
import zlib

ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / 'skills/shello-pair/scripts'
spec = importlib.util.spec_from_file_location('pair_skill', SCRIPTS / 'start_pairing.py')
PAIR = importlib.util.module_from_spec(spec)
spec.loader.exec_module(PAIR)


class PairSkillTest(unittest.TestCase):
    def bash(self, body, *args, env=None):
        return subprocess.run(['bash', '-c', 'source "$1"; shift; ' + body,
                               'skill-test', str(SCRIPTS / 'bootstrap.sh'), *map(str, args)],
                              capture_output=True, text=True, env=env)

    def test_install_only_missing_apt_packages_without_running_installer(self):
        result = self.bash('''
missing_dependencies(){ printf 'python\\ntmux\\nsshd\\nssh-keygen\\n'; }
package_manager(){ echo apt-get; }
as_root(){ printf 'CALL'; printf ' <%s>' "$@"; printf '\\n'; }
install_missing
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(['CALL <apt-get> <update>',
                          'CALL <apt-get> <install> <-y> <python3> <python3-venv> <tmux> <openssh-server> <openssh-client>'], result.stdout.splitlines())

    def test_dnf_python_mapping_and_no_unrelated_packages(self):
        result = self.bash('''
missing_dependencies(){ echo python; }; package_manager(){ echo dnf; }
as_root(){ printf '<%s>' "$@"; }; install_missing
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual('<dnf><install><-y><python3><python3-pip>', result.stdout)

    def test_no_install_and_no_service_restart_when_already_ready(self):
        result = self.bash('''
missing_dependencies(){ :; }; package_manager(){ return 98; }; as_root(){ return 99; }
ssh_ready(){ return 0; }; install_missing; ssh_port=2222; configure_ssh
''')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('nothing to install', result.stdout)
        self.assertIn('port 2222', result.stdout)

    def test_no_privilege_requests_when_only_checking(self):
        result = self.bash('''
id(){ echo 501; }; missing_dependencies(){ echo python; }
ssh_ready(){ return 1; }; tmux_probe(){ return 1; }; as_root(){ exit 99; }
main --check --ssh-port 2222
''')
        self.assertEqual(2, result.returncode)
        self.assertIn('MISSING=python', result.stdout)
        self.assertIn('SSH_READY=false PORT=2222', result.stdout)

    def test_unavailable_administrator_auth_stops_without_prompting(self):
        result = self.bash('id(){ echo 501; }; sudo(){ return 1; }; as_root true')
        self.assertEqual(77, result.returncode)
        self.assertIn('Do not send a password', result.stderr)

    def test_invalid_port_never_configures_service(self):
        result = self.bash('configure_ssh(){ exit 99; }; main --configure-ssh --ssh-port 70000')
        self.assertEqual(2, result.returncode)
        self.assertIn('Invalid SSH port', result.stderr)

    @unittest.skipUnless(shutil.which('tmux'), 'tmux is required for the isolated server check')
    def test_tmux_probe_preserves_an_existing_server(self):
        with tempfile.TemporaryDirectory(prefix='sps-', dir='/tmp') as temp:
            socket_path = str(Path(temp) / 'original.sock')
            command = ['tmux', '-S', socket_path]
            subprocess.run(command + ['-f', '/dev/null', 'new-session', '-d', '-s', 'untouched', 'sleep 30'], check=True)
            try:
                before = subprocess.check_output(command + ['display-message', '-p', '-t', 'untouched', '#{pane_pid}'])
                result = self.bash('tmux_probe', env=dict(os.environ, TMUX=socket_path + ',1,0'))
                self.assertEqual(0, result.returncode, result.stderr)
                after = subprocess.check_output(command + ['display-message', '-p', '-t', 'untouched', '#{pane_pid}'])
                self.assertEqual(before, after)
            finally:
                subprocess.run(command + ['kill-server'], check=False, capture_output=True)

    def test_standalone_launcher_rejects_download_with_wrong_checksum(self):
        with tempfile.TemporaryDirectory(prefix='sps-', dir='/tmp') as temp:
            folder = Path(temp)
            with patch.object(PAIR, '__file__', str(folder / 'copy/scripts/start_pairing.py')), \
                    patch.object(PAIR, 'urlopen', return_value=io.BytesIO(b'not the official launcher')):
                with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                    PAIR.select_launcher(None, folder)
            self.assertFalse((folder / 'pair.sh').exists())

    def test_startup_failure_redacts_ticket_and_token(self):
        with tempfile.TemporaryDirectory(prefix='sps-', dir='/tmp') as temp:
            log = Path(temp) / 'log'
            log.write_text('Failed ghostty-pair://v1?token=SECRET token=SECRET2')
            result = PAIR.startup_diagnostic(log)
            self.assertNotIn('SECRET', result)

    @unittest.skipUnless(shutil.which('tmux') and shutil.which('openssl') and shutil.which('ssh-keygen') and os.geteuid() != 0,
                         'HTTPS integration requires Unix tools and a non-root test user')
    def test_qr_https_public_key_and_signal_cleanup(self):
        with tempfile.TemporaryDirectory(prefix='sps-', dir='/tmp') as temp, socket.socket() as ssh:
            folder = Path(temp)
            ssh.bind(('127.0.0.1', 0)); ssh.listen()
            key = folder / 'client'
            subprocess.run(['ssh-keygen', '-q', '-t', 'rsa', '-b', '3072', '-N', '', '-f', str(key)], check=True)
            public = key.with_suffix('.pub').read_text().strip()
            authorized = folder / 'authorized_keys'
            original = '# preserve existing entries and options\n'
            authorized.write_text(original)
            env = dict(os.environ, SHELLO_PAIR_HOME=str(ROOT / 'build/pair-launcher-env'), TMPDIR=temp)
            process = subprocess.Popen([sys.executable, str(SCRIPTS / 'start_pairing.py'), '--network', 'lan', '--host', '127.0.0.1',
                                        '--ssh-port', str(ssh.getsockname()[1]), '--host-key', str(key.with_suffix('.pub')),
                                        '--authorized-keys', str(authorized), '--launcher', str(ROOT / 'companion/pair.sh'), '--ttl', '60'],
                                       env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            events = queue.Queue()
            def read_events():
                for line in process.stdout:
                    events.put(json.loads(line))
            reader = threading.Thread(target=read_events, daemon=True); reader.start()
            seen = []
            def until(event, timeout=90):
                deadline = time.monotonic() + timeout
                while time.monotonic() < deadline:
                    item = events.get(timeout=max(.1, deadline - time.monotonic())); seen.append(item)
                    if item['event'] == event: return item
                    if item['event'] in ('failed', 'startup_failed'): self.fail(str(item))
                self.fail('Pairing event timed out')
            ready = None
            try:
                ready = until('ready')
                image, payload = Path(ready['qr_image']), Path(ready['qr_payload_file'])
                self.assertEqual(0o600, image.stat().st_mode & 0o777)
                self.assertEqual(0o700, image.parent.stat().st_mode & 0o777)
                png = image.read_bytes(); self.assertEqual(b'\x89PNG\r\n\x1a\n', png[:8])
                width, height = struct.unpack('!II', png[16:24]); self.assertEqual(width, height)
                self.assertGreater(width, 200)
                compressed = b''; offset = 8
                while offset < len(png):
                    size, = struct.unpack('!I', png[offset:offset+4]); kind = png[offset+4:offset+8]; data = png[offset+8:offset+8+size]
                    self.assertEqual(zlib.crc32(kind + data) & 0xffffffff, struct.unpack('!I', png[offset+8+size:offset+12+size])[0])
                    if kind == b'IDAT': compressed += data
                    offset += size + 12
                self.assertEqual(height * (width + 1), len(zlib.decompress(compressed)))
                fields = PAIR.ticket(payload.read_text())
                with self.assertRaisesRegex(ValueError, 'pin mismatch'):
                    PAIR.verify_listener(dict(fields, pin='0' * 64))
                def post(token):
                    connection = http.client.HTTPSConnection('127.0.0.1', int(fields['port']), timeout=5, context=ssl._create_unverified_context())
                    connection.connect()
                    self.assertEqual(fields['pin'], hashlib.sha256(connection.sock.getpeercert(binary_form=True)).hexdigest())
                    connection.request('POST', '/pair', json.dumps({'token': token, 'publicKey': public}), {'Content-Type': 'application/json'})
                    response = connection.getresponse(); status = response.status; response.read(); connection.close(); return status
                self.assertEqual(403, post('wrong-token'))
                self.assertEqual(original, authorized.read_text())
                self.assertEqual(200, post(fields['token']))
                until('public_key_installed', timeout=5)
                self.assertTrue(authorized.read_text().startswith(original))
                self.assertEqual(0o600, authorized.stat().st_mode & 0o777)
                self.assertEqual(1, authorized.read_text().count(' '.join(public.split()[:2])))
                self.assertNotIn(fields['token'], json.dumps(seen))
            finally:
                if process.poll() is None: process.send_signal(signal.SIGTERM)
                try: process.wait(timeout=10)
                except subprocess.TimeoutExpired: process.kill(); process.wait(); self.fail('Pairing cleanup hung')
                reader.join(timeout=2)
                errors = process.stderr.read(); process.stdout.close(); process.stderr.close()
            self.assertEqual(130, process.returncode, errors)
            self.assertFalse(Path(ready['qr_image']).parent.exists())
            with self.assertRaises(OSError): socket.create_connection(('127.0.0.1', int(fields['port'])), timeout=1)


if __name__ == '__main__':
    unittest.main(verbosity=2)
