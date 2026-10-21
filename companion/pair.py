#!/usr/bin/env python3
"""One-time local HTTPS pairing for Shello. Requires Python 3, openssl,
and qrcode (`python3 -m pip install 'qrcode==8.2'`). SSH must already be enabled.
Only the phone's PUBLIC key is added to authorized_keys. No private SSH key leaves
its device. The self-signed HTTPS certificate is pinned by the scanned QR code.
"""
import argparse
import base64
import fcntl
import getpass
import hashlib
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import ssl
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlencode


def public_key(value):
    fields = value.strip().split()
    if len(fields) < 2 or fields[0] != 'ssh-rsa':
        raise ValueError('expected RSA public key')
    raw = base64.b64decode(fields[1], validate=True)
    offset = 0
    parts = []
    for _ in range(3):
        n = int.from_bytes(raw[offset:offset + 4], 'big')
        offset += 4
        if n < 1 or offset + n > len(raw):
            raise ValueError('bad public key')
        parts.append(raw[offset:offset + n])
        offset += n
    if offset != len(raw) or parts[0] != b'ssh-rsa' or int.from_bytes(parts[2], 'big').bit_length() < 3072:
        raise ValueError('weak or malformed key')
    return 'ssh-rsa ' + fields[1]


def authorize(path, key, pairing_id):
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.is_symlink() or path.parent.is_symlink():
        raise ValueError('symlink is not supported for pairing')
    # Appends under a lock; preserves every existing entry and its key options.
    fd = os.open(path, os.O_RDWR | os.O_APPEND | os.O_CREAT | getattr(os, 'O_NOFOLLOW', 0), 0o600)
    with os.fdopen(fd, 'a+') as stream:
        fcntl.flock(stream, fcntl.LOCK_EX)
        os.fchmod(stream.fileno(), 0o600)
        stream.seek(0)
        existing = stream.read()
        line = key + ' ghostty-android-' + pairing_id
        if line not in existing.splitlines():
            stream.write(('\n' if existing and not existing.endswith('\n') else '') + line + '\n')
            stream.flush()
            os.fsync(stream.fileno())


TAILNET = ipaddress.ip_network('100.64.0.0/10')
LAN = tuple(map(ipaddress.ip_network, ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16', '127.0.0.0/8')))


def validate_host(host):
    address = ipaddress.ip_address(host)
    if address.version != 4 or not any(address in network for network in (*LAN, TAILNET)):
        raise ValueError('use a LAN or Tailscale IPv4 address')
    return str(address)


def tailscale_host():
    candidates = [shutil.which('tailscale'), '/Applications/Tailscale.app/Contents/MacOS/Tailscale',
                  '/opt/homebrew/bin/tailscale', '/usr/local/bin/tailscale', '/usr/bin/tailscale']
    for binary in dict.fromkeys(candidates):
        if not binary or not os.access(binary, os.X_OK):
            continue
        try:
            result = subprocess.run([binary, 'status', '--json'], capture_output=True, timeout=5, check=True)
            if len(result.stdout) > 1048576:
                continue
            data = json.loads(result.stdout)
            if data.get('BackendState') != 'Running':
                continue
            for value in data.get('Self', {}).get('TailscaleIPs', []):
                if ipaddress.ip_address(value) in TAILNET:
                    return str(ipaddress.ip_address(value))
        except (OSError, ValueError, subprocess.SubprocessError):
            continue
    return None


def select_host(host=None, network='auto'):
    if host:
        selected = validate_host(host)
    else:
        selected = tailscale_host() if network != 'lan' else None
        if selected is None:
            if network == 'tailscale':
                raise ValueError('Tailscale is not connected or its CLI is unavailable; start Tailscale or pass --host with its IPv4 address')
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as route:
                route.connect(('192.0.2.1', 9))
                selected = validate_host(route.getsockname()[0])
    is_tailnet = ipaddress.ip_address(selected) in TAILNET
    if network == 'tailscale' and not is_tailnet or network == 'lan' and is_tailnet:
        raise ValueError('--host does not match --network')
    return selected


def serve(args):
    os.umask(0o077)
    args.host = select_host(args.host, args.network)
    print('Pairing over ' + ('Tailscale' if ipaddress.ip_address(args.host) in TAILNET else 'LAN') +
          ' at ' + args.host + '. Keep both devices connected to this network.', flush=True)
    with socket.create_connection((args.bind or args.host, args.ssh_port), timeout=3):
        pass
    keys = []
    for path in args.host_key or Path('/etc/ssh').glob('ssh_host_*_key.pub'):
        fields = Path(path).read_text().split()
        if len(fields) >= 2 and fields[0] in ('ssh-ed25519', 'ssh-rsa', 'ecdsa-sha2-nistp256', 'ecdsa-sha2-nistp384', 'ecdsa-sha2-nistp521'):
            keys.append(' '.join(fields[:2]))
    if not keys:
        raise ValueError('SSH host public keys unavailable; specify --host-key')
    token = secrets.token_urlsafe(32)
    pairing_id = secrets.token_hex(8)
    deadline = time.time() + args.ttl
    accepted = [None]
    paired_at = [None]
    lock = threading.Lock()
    class Handler(BaseHTTPRequestHandler):
        def setup(self):
            super().setup()
            self.connection.settimeout(5)
        def log_message(self, *unused):
            pass  # Never log pairing tokens or request bodies.
        def do_POST(self):
            try:
                n = int(self.headers.get('Content-Length', '0'))
                if self.path != '/pair' or n <= 0 or n > 16384:
                    raise ValueError('bad request')
                value = json.loads(self.rfile.read(n))
                if time.time() > deadline or not hmac.compare_digest(str(value.get('token', '')), token):
                    raise ValueError('expired or invalid token')
                key = public_key(value['publicKey'])
                with lock:
                    if accepted[0] is not None and accepted[0] != key:
                        raise ValueError('token already used')
                    authorize(args.authorized_keys, key, pairing_id)
                    accepted[0] = key
                    if paired_at[0] is None:
                        paired_at[0] = time.time()
                        print('Phone public key installed. Verify the SSH connection on your phone.', flush=True)
                body = json.dumps({'version': 1, 'user': args.user, 'name': socket.gethostname(), 'port': args.ssh_port, 'hostKeys': keys}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except (ValueError, KeyError, OSError):
                self.send_error(403, 'Pairing rejected or expired')
    with tempfile.TemporaryDirectory(prefix='ghostty-pair-') as temp:
        folder = Path(temp)
        key, cert = folder / 'key.pem', folder / 'cert.pem'
        subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:3072', '-nodes', '-keyout', str(key), '-out', str(cert), '-days', '1', '-subj', '/CN=Shello Pair'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        fingerprint = hashlib.sha256(ssl.PEM_cert_to_DER_cert(cert.read_text())).hexdigest()
        server = ThreadingHTTPServer((args.bind or args.host, args.port), Handler)
        server.daemon_threads = True
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = ssl.TLSVersion.TLSv1_2
        tls.load_cert_chain(cert, key)
        server.socket = tls.wrap_socket(server.socket, server_side=True)
        uri = 'ghostty-pair://v1?' + urlencode({'host': args.host, 'port': server.server_port, 'pin': fingerprint, 'token': token, 'expires': int(deadline)})
        if args.qr_file:
            args.qr_file.write_text(uri)
            args.qr_file.chmod(0o600)
        import qrcode
        qr = qrcode.QRCode(border=2)
        qr.add_data(uri)
        qr.make(fit=True)
        print('Scan in Shello → + → 扫码轻松配对. Expires in', args.ttl, 'seconds.', flush=True)
        qr.print_ascii(invert=True)
        print('Only scan this QR on your own phone. Ctrl-C closes pairing.', flush=True)
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        try:
            while time.time() < deadline and (paired_at[0] is None or time.time() < paired_at[0] + 30):
                time.sleep(.25)
        finally:
            server.shutdown()
            server.server_close()
        print('Pairing closed.' + (' Public key installed.' if accepted[0] else ' No key installed.'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', help='Computer LAN or Tailscale IPv4 address')
    parser.add_argument('--network', choices=('auto', 'lan', 'tailscale'), default='auto',
                        help='auto prefers connected Tailscale, otherwise LAN (default: auto)')
    parser.add_argument('--ssh-port', type=int, default=22)
    parser.add_argument('--port', type=int, default=0, help='Temporary HTTPS port; default random')
    parser.add_argument('--user', default=getpass.getuser())
    parser.add_argument('--ttl', type=int, default=180)
    parser.add_argument('--bind', help=argparse.SUPPRESS)
    parser.add_argument('--host-key', type=Path, action='append', help='SSH host public key file (default /etc/ssh/*pub)')
    parser.add_argument('--authorized-keys', type=Path, default=Path.home() / '.ssh/authorized_keys')
    parser.add_argument('--qr-file', type=Path, help='Optional private QR payload file for tests')
    args = parser.parse_args()
    if not 30 <= args.ttl <= 600:
        parser.error('--ttl must be 30..600')
    try:
        serve(args)
    except KeyboardInterrupt:
        print('Pairing stopped.')


if __name__ == '__main__':
    main()
