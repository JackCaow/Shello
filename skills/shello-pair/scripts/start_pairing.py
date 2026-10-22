#!/usr/bin/env python3
"""Keep Shello pairing alive, emit a private QR image path, and clean up on exit."""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import signal
import socket
import ssl
import subprocess
import sys
import tempfile
import time
from urllib.parse import parse_qs, urlparse
from urllib.request import urlopen

RELEASE_URL = 'https://raw.githubusercontent.com/JackCaow/Shello/v0.1.0/companion/pair.sh'
RELEASE_SHA256 = '1be4e668458e8d40ec6da4f176f405a81af3d96126c69c22909528944f31497a'


def emit(event, **fields):
    print(json.dumps(dict(event=event, **fields)), flush=True)


def select_launcher(explicit, folder):
    if explicit:
        path = explicit.expanduser().resolve()
        if not path.is_file():
            raise ValueError('Specified launcher is not a file')
        return path
    repository_root = Path(__file__).resolve().parents[3]
    repository_launcher = repository_root / 'companion/pair.sh'
    if (repository_root / '.git').exists() and repository_launcher.is_file():
        return repository_launcher
    with urlopen(RELEASE_URL, timeout=30) as response:
        content = response.read(1048577)
    if len(content) > 1048576 or hashlib.sha256(content).hexdigest() != RELEASE_SHA256:
        raise ValueError('Official release launcher checksum mismatch; refusing to execute')
    path = folder / 'pair.sh'
    path.write_bytes(content)
    path.chmod(0o700)
    return path


def ticket(payload):
    uri = urlparse(payload)
    if uri.scheme != 'ghostty-pair' or uri.netloc != 'v1':
        raise ValueError('Unrecognized pairing protocol')
    fields = {key: values[0] for key, values in parse_qs(uri.query).items()}
    if any(key not in fields for key in ('host', 'port', 'pin', 'token', 'expires')):
        raise ValueError('Incomplete pairing ticket')
    if len(fields['pin']) != 64 or any(c not in '0123456789abcdef' for c in fields['pin']):
        raise ValueError('Invalid certificate pin')
    if not 1 <= int(fields['port']) <= 65535 or int(fields['expires']) <= time.time():
        raise ValueError('Invalid port or expired pairing ticket')
    return fields


def verify_listener(fields, connect_host=None, connect_port=None):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE  # The scanned, random session certificate is pinned below.
    with socket.create_connection((connect_host or fields['host'], connect_port or int(fields['port'])), timeout=2) as connection:
        with context.wrap_socket(connection, server_hostname=fields['host']) as tls:
            if hashlib.sha256(tls.getpeercert(binary_form=True)).hexdigest() != fields['pin']:
                raise ValueError('Pairing service certificate pin mismatch')


def startup_diagnostic(log_path):
    lines = log_path.read_text(errors='replace').strip().splitlines()
    message = lines[-1] if lines else 'No launcher diagnostic'
    message = re.sub(r'ghostty-pair://\S+', '[redacted ticket]', message)
    return re.sub(r"(?i)token[=:\s]+[^&\s]+", 'token=[redacted]', message)[:400]


def stop_process_group(process):
    # Even if the launcher shell has exited, its Python child can still own this group.
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)


def run(options):
    if os.geteuid() == 0:
        raise ValueError('Run pairing as the intended ordinary SSH user, not root')
    os.umask(0o077)
    # These paths are local session artifacts, never a source-controlled output directory.
    with tempfile.TemporaryDirectory(prefix='shello-pair-skill-') as temp:
        folder = Path(temp)
        launcher = select_launcher(options.launcher, folder)
        payload, image, log_path = folder / 'qr.txt', folder / 'qr.png', folder / 'pair.log'
        command = ['bash', str(launcher), '--network', options.network, '--ssh-port', str(options.ssh_port),
                   '--port', str(options.port), '--ttl', str(options.ttl), '--qr-file', str(payload)]
        if options.host:
            command += ['--host', options.host]
        for flag, value in [('--bind', options.bind), ('--ssh-check-port', options.ssh_check_port), ('--advertise-port', options.advertise_port), ('--name', options.name)]:
            if value is not None: command += [flag, str(value)]
        for key in options.host_key:
            command += ['--host-key', str(key.expanduser().resolve())]
        if options.authorized_keys:
            command += ['--authorized-keys', str(options.authorized_keys.expanduser().resolve())]
        env = dict(os.environ)
        env['PATH'] = '/opt/homebrew/bin:/usr/local/bin:/opt/homebrew/opt/openssl@3/bin:/usr/local/opt/openssl@3/bin:' + env.get('PATH', '')
        pair_home = Path(env.get('SHELLO_PAIR_HOME', str(Path.home() / '.local/share/shello/pair')))
        renderer_python = pair_home / 'venv/bin/python'
        renderer = Path(__file__).resolve().with_name('render_qr.py')
        with log_path.open('wb') as log:
            process = subprocess.Popen(command, env=env, stdout=log, stderr=log, start_new_session=True)
            try:
                startup_deadline = time.monotonic() + 120
                emit('starting')
                while not payload.is_file() or payload.stat().st_size == 0:
                    if process.poll() is not None or time.monotonic() > startup_deadline:
                        emit('startup_failed', diagnostic=startup_diagnostic(log_path))
                        raise RuntimeError('Pairing launcher did not become ready')
                    time.sleep(.1)
                fields = ticket(payload.read_text())
                ready_deadline = time.monotonic() + 10
                while True:
                    try:
                        probe_host='127.0.0.1' if options.bind=='0.0.0.0' else options.bind
                        verify_listener(fields,probe_host,options.port if options.bind and options.port else None)
                        break
                    except OSError:
                        if process.poll() is not None or time.monotonic() > ready_deadline:
                            raise RuntimeError('Pairing HTTPS listener did not become ready')
                        time.sleep(.1)
                subprocess.run([str(renderer_python), str(renderer), str(payload), str(image)], check=True)
                emit('ready', host=fields['host'],port=int(fields['port']),certificate_sha256=fields['pin'],qr_image=str(image), expires_at_unix=int(fields['expires']),
                     qr_payload_file=str(payload), renderer_python=str(renderer_python))
                installed = False
                completion_deadline = time.monotonic() + options.ttl + 45
                while process.poll() is None:
                    if not installed and 'Phone public key installed.' in log_path.read_text(errors='replace'):
                        installed = True
                        emit('public_key_installed', phone_ssh_verification='pending')
                    if time.monotonic() > completion_deadline:
                        raise RuntimeError('Pairing launcher exceeded its expected lifetime')
                    time.sleep(.25)
                installed = installed or 'Phone public key installed.' in log_path.read_text(errors='replace')
                if process.returncode != 0:
                    raise RuntimeError('Pairing launcher exited unsuccessfully')
                emit('closed', public_key_installed=installed, phone_ssh_verification='not_observed', temporary_files_removed_on_exit=True)
                return 0 if installed else 2
            finally:
                stop_process_group(process)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--network', choices=('auto', 'lan', 'tailscale'), default='auto')
    parser.add_argument('--host')
    parser.add_argument('--bind', help='Internal listener IPv4 for a forwarded endpoint')
    parser.add_argument('--ssh-check-port', type=int)
    parser.add_argument('--advertise-port', type=int)
    parser.add_argument('--name')
    parser.add_argument('--ssh-port', type=int, default=22)
    parser.add_argument('--port', type=int, default=0)
    parser.add_argument('--ttl', type=int, default=300)
    parser.add_argument('--launcher', type=Path, help='Use an explicitly trusted local pairing launcher')
    parser.add_argument('--host-key', action='append', type=Path, default=[], help='Host public key for a nonstandard SSH server')
    parser.add_argument('--authorized-keys', type=Path, help='Nonstandard authorized_keys path; default is the current user')
    options = parser.parse_args()
    if any(p is not None and not 1 <= p <= 65535 for p in (options.ssh_check_port,options.advertise_port)):
        parser.error('Invalid forwarded port')
    if not 30 <= options.ttl <= 600 or not 1 <= options.ssh_port <= 65535 or not 0 <= options.port <= 65535:
        parser.error('Use ttl 30..600, ssh-port 1..65535 and port 0..65535')
    try:
        return run(options)
    except (KeyboardInterrupt, SystemExit):
        emit('cancelled')
        return 130
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        emit('failed', reason=str(error))
        return 1


if __name__ == '__main__':
    def interrupted(*_):
        raise KeyboardInterrupt
    signal.signal(signal.SIGTERM, interrupted)
    sys.exit(main())
