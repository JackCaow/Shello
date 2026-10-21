#!/usr/bin/env python3
"""Opt-in macOS/Android-emulator SSH creation checks with disposable local servers.

Run with a Python environment containing paramiko==5.1.0. Build sample debug and
androidTest APKs first. No credentials or configuration from ~/.ssh are used.
"""
import argparse
import getpass
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time


def password_server(root, port):
    import paramiko
    values = dict(line.split('=', 1) for line in (root / 'test.properties').read_text().splitlines())
    key = paramiko.Ed25519Key.from_private_key_file(str(root / 'host_key'))

    class Server(paramiko.ServerInterface):
        def check_auth_password(self, user, password):
            return paramiko.AUTH_SUCCESSFUL if user == values['user'] and password == values['password'] else paramiko.AUTH_FAILED

        def get_allowed_auths(self, user):
            return 'password'

        def check_channel_request(self, kind, channel_id):
            return paramiko.OPEN_SUCCEEDED if kind == 'session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

        def check_channel_pty_request(self, *args):
            return True

        def check_channel_window_change_request(self, *args):
            return True

        def check_channel_shell_request(self, channel):
            threading.Thread(target=self.echo, args=(channel,), daemon=True).start()
            return True

        def echo(self, channel):
            try:
                channel.sendall(b'GHOSTTY_PASSWORD_READY\r\n$ ')
                while data := channel.recv(4096):
                    channel.sendall(data)
            except Exception:
                pass
            finally:
                channel.close()

    def client(connection):
        transport = paramiko.Transport(connection)
        try:
            transport.add_server_key(key)
            transport.start_server(server=Server())
            while transport.is_active():
                channel = transport.accept(1)
                if channel:
                    while transport.is_active() and not channel.closed:
                        time.sleep(.05)
        except Exception:
            pass  # Expected for cancellation and host-pin rejection cases.
        finally:
            transport.close()

    with socket.socket() as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind(('127.0.0.1', port))
        server.listen()
        while True:
            connection, _ = server.accept()
            threading.Thread(target=client, args=(connection,), daemon=True).start()


def free_port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', help='Dedicated Android emulator serial; installs the sample and its tests')
    parser.add_argument('--serve-password', type=Path, help=argparse.SUPPRESS)
    parser.add_argument('--port', type=int, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.serve_password:
        password_server(args.serve_password, args.port)
        return
    if sys.platform != 'darwin' or not args.serial or not args.serial.startswith('emulator-'):
        parser.error('Use macOS and --serial emulator-XXXX for this isolated fixture')
    try:
        import paramiko  # noqa: F401
    except ImportError:
        parser.error('Install paramiko==5.1.0 in a disposable Python environment first')
    repo = Path(__file__).resolve().parents[1]
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Library/Android/sdk')))
    adb = [str(sdk / 'platform-tools/adb'), '-s', args.serial]
    package = 'dev.ghostty.android.sample'
    cache = Path.home() / '.cache'
    cache.mkdir(exist_ok=True)
    # OpenSSH StrictModes rejects world-writable /tmp ancestors, even with a 0700 leaf.
    with tempfile.TemporaryDirectory(prefix='ghostty-connection-flow-', dir=cache) as folder:
        root = Path(folder)
        os.chmod(root, 0o700)
        servers, logs = [], []
        try:
            for name in ('client_key', 'host_key'):
                subprocess.run(['ssh-keygen', '-q', '-t', 'ed25519', '-N', '', '-f', str(root / name)], check=True)
            (root / 'authorized_keys').write_bytes((root / 'client_key.pub').read_bytes())
            os.chmod(root / 'authorized_keys', 0o600)
            ssh_port, password_port = free_port(), free_port()
            while password_port == ssh_port:
                password_port = free_port()
            passphrase=secrets.token_hex(24)
            shutil.copy(root / 'client_key',root / 'client_key_encrypted')
            subprocess.run(['ssh-keygen','-q','-p','-P','','-N',passphrase,'-f',str(root / 'client_key_encrypted')],check=True,stdout=subprocess.DEVNULL)
            (root / 'test.properties').write_text(f'host=10.0.2.2\nport={ssh_port}\nuser={getpass.getuser()}\npasswordPort={password_port}\npassword={secrets.token_hex(24)}\npassphrase={passphrase}\n')
            os.chmod(root / 'test.properties', 0o600)
            (root / 'sshd_config').write_text(f'''ListenAddress 127.0.0.1
Port {ssh_port}
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
''')
            subprocess.run(['/usr/sbin/sshd', '-t', '-f', str(root / 'sshd_config')], check=True)
            commands = [(['/usr/sbin/sshd', '-D', '-e', '-f', str(root / 'sshd_config')], ssh_port),
                        ([sys.executable, str(Path(__file__).resolve()), '--serve-password', str(root), '--port', str(password_port)], password_port)]
            for command, port in commands:
                log = (root / f'server-{port}.log').open('w'); logs.append(log)
                server = subprocess.Popen(command, stdout=log, stderr=log, start_new_session=True); servers.append(server)
                deadline = time.monotonic() + 10
                while True:
                    if server.poll() is not None or time.monotonic() > deadline:
                        raise RuntimeError('Local fixture did not start')
                    with socket.socket() as probe:
                        if probe.connect_ex(('127.0.0.1', port)) == 0:
                            break
                    time.sleep(.1)
            for apk in ('sample/build/outputs/apk/debug/sample-debug.apk', 'sample/build/outputs/apk/androidTest/debug/sample-debug-androidTest.apk'):
                subprocess.run(adb + ['install', '-r', str(repo / apk)], check=True)
            subprocess.run(adb + ['shell', 'run-as', package, 'mkdir', '-p', 'cache/ssh-flow'], check=True)
            for name in ('test.properties', 'client_key', 'client_key_encrypted'):
                subprocess.run(adb + ['shell', f"run-as {package} sh -c 'cat > cache/ssh-flow/{name}'"], input=(root / name).read_bytes(), check=True)
            classes = ','.join(package + '.' + name for name in ('SshProfileTest', 'SshConnectionFlowTest', 'SshResumeStateTest'))
            result = subprocess.run(adb + ['shell', 'am', 'instrument', '-w', '-e', 'class', classes, package + '.test/androidx.test.runner.AndroidJUnitRunner'], capture_output=True, text=True, timeout=180)
            print(result.stdout)
            if result.returncode or 'FAILURES' in result.stdout or 'OK (6 tests)' not in result.stdout:
                raise RuntimeError('Connection flow tests failed')
        finally:
            subprocess.run(adb + ['shell', 'run-as', package, 'rm', '-rf', 'cache/ssh-flow'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            for server in servers:
                if server.poll() is None:
                    os.killpg(server.pid, signal.SIGTERM)
                    try:
                        server.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        os.killpg(server.pid, signal.SIGKILL); server.wait()
            for log in logs:
                log.close()


if __name__ == '__main__':
    main()
