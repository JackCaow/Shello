#!/usr/bin/env python3
"""Tiny SSH server that accepts one username/password and runs `exec` requests (for iOS password-auth tests).
usage: ios-test-passwd-server.py <port> <user> <password> <pidfile-dir>"""
import os, socket, subprocess, sys, threading
import paramiko

port, user, password, folder = int(sys.argv[1]), sys.argv[2], sys.argv[3], sys.argv[4]
host_key = paramiko.Ed25519Key.generate() if hasattr(paramiko.Ed25519Key, 'generate') else paramiko.RSAKey.generate(2048)

class Server(paramiko.ServerInterface):
    def get_allowed_auths(self, username): return 'password'
    def check_auth_password(self, username, pw):
        return paramiko.AUTH_SUCCESSFUL if (username, pw) == (user, password) else paramiko.AUTH_FAILED
    def check_channel_request(self, kind, chanid): return paramiko.OPEN_SUCCEEDED if kind == 'session' else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED
    def check_channel_exec_request(self, channel, command):
        def run():
            out = subprocess.run(command.decode(), shell=True, capture_output=True)
            channel.sendall(out.stdout); channel.send_exit_status(out.returncode); channel.shutdown_write(); channel.close()
        threading.Thread(target=run, daemon=True).start(); return True

def handle(client):
    t = paramiko.Transport(client); t.add_server_key(host_key)
    try: t.start_server(server=Server())
    except Exception: pass

sock = socket.socket(); sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); sock.bind(('127.0.0.1', port)); sock.listen(20)
pub = f'{host_key.get_name()} {host_key.get_base64()}'
open(os.path.join(folder, 'passwd_host.pub'), 'w').write(pub + '\n')
open(os.path.join(folder, 'passwd.pid'), 'w').write(str(os.getpid()))
while True:
    c, _ = sock.accept(); threading.Thread(target=handle, args=(c,), daemon=True).start()
