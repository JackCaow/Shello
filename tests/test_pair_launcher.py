"""Local integration check; only temporary keys/authorized_keys are used."""
import hashlib
import http.client
import json
import os
from pathlib import Path
import signal
import socket
import ssl
import subprocess
import tempfile
import time
import unittest
from urllib.parse import urlparse, parse_qs

ROOT = Path(__file__).resolve().parents[1]

class PairLauncherTest(unittest.TestCase):
    def test_embedded_helper_and_help(self):
        script = (ROOT / 'companion/pair.sh').read_text()
        embedded = script.split("<<'SHELLO_PAIR_PYTHON'\n", 1)[1].split('\nSHELLO_PAIR_PYTHON\n', 1)[0]
        self.assertEqual((ROOT / 'companion/pair.py').read_text(), embedded)
        result = subprocess.run(['bash', str(ROOT / 'companion/pair.sh'), '--help'], capture_output=True, text=True, check=True)
        self.assertIn('--ssh-port', result.stdout)

    def test_https_pairing_rejects_bad_token_and_installs_only_temp_public_key(self):
        with tempfile.TemporaryDirectory(prefix='shello-pair-test-') as temp:
            folder = Path(temp)
            # A listener satisfies the existing SSH availability preflight without changing sshd.
            with socket.socket() as ssh, (folder / 'service.log').open('wb') as log:
                ssh.bind(('127.0.0.1', 0)); ssh.listen()
                key = folder / 'client'
                subprocess.run(['ssh-keygen', '-q', '-t', 'rsa', '-b', '3072', '-N', '', '-f', str(key)], check=True)
                public = key.with_suffix('.pub').read_text().strip()
                authorized = folder / 'ssh/authorized_keys'
                qr_file = folder / 'qr'
                env = dict(os.environ, SHELLO_PAIR_HOME=str(ROOT / 'build/pair-launcher-env'), TMPDIR=temp)
                proc = subprocess.Popen(['bash', str(ROOT / 'companion/pair.sh'), '--host', '127.0.0.1', '--ssh-port', str(ssh.getsockname()[1]), '--host-key', str(key.with_suffix('.pub')), '--authorized-keys', str(authorized), '--qr-file', str(qr_file), '--ttl', '60'], env=env, stdout=log, stderr=log, start_new_session=True)
                try:
                    deadline = time.monotonic() + 90
                    while not qr_file.exists() and time.monotonic() < deadline and proc.poll() is None:
                        time.sleep(.1)
                    self.assertTrue(qr_file.exists(), 'Pairing server failed to start; inspect private local test log')
                    ticket = {k: v[0] for k, v in parse_qs(urlparse(qr_file.read_text()).query).items()}
                    context = ssl._create_unverified_context()
                    def post(token):
                        connection = http.client.HTTPSConnection('127.0.0.1', int(ticket['port']), context=context, timeout=5)
                        connection.connect()
                        self.assertEqual(ticket['pin'], hashlib.sha256(connection.sock.getpeercert(binary_form=True)).hexdigest())
                        connection.request('POST', '/pair', json.dumps({'token': token, 'publicKey': public}), {'Content-Type': 'application/json'})
                        response = connection.getresponse(); status, body = response.status, response.read(); connection.close()
                        return status, body
                    # QR is written immediately before the HTTPS worker starts.
                    deadline = time.monotonic() + 5
                    while True:
                        try: status, _ = post('invalid'); break
                        except OSError:
                            if time.monotonic() > deadline: raise
                            time.sleep(.1)
                    self.assertEqual(403, status); self.assertFalse(authorized.exists())
                    status, body = post(ticket['token']); self.assertEqual(200, status)
                    self.assertEqual(1, json.loads(body)['version'])
                    self.assertEqual(200, post(ticket['token'])[0])
                    self.assertEqual(1, len(authorized.read_text().splitlines()))
                    self.assertTrue(authorized.read_text().startswith(' '.join(public.split()[:2])))
                    self.assertEqual(0o600, authorized.stat().st_mode & 0o777)
                finally:
                    if proc.poll() is None:
                        os.killpg(proc.pid, signal.SIGINT)
                        try: proc.wait(timeout=10)
                        except subprocess.TimeoutExpired:
                            os.killpg(proc.pid, signal.SIGKILL); proc.wait()
                self.assertEqual([], list(folder.glob('shello-pair.*')), 'Launcher temporary helper was not cleaned up')

if __name__ == '__main__':
    unittest.main(verbosity=2)
