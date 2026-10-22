#!/usr/bin/env bash
# Invoked only in the selected WSL distribution; never replaces its normal SSH service.
set -euo pipefail
linux_user=${1:?Linux user}; ssh_port=${2:?SSH port}; owner=${3:?Owner ID}
[[ "$linux_user" =~ ^[a-z_][a-z0-9_-]{0,31}$ && "$ssh_port" =~ ^[0-9]{1,5}$ && "$owner" =~ ^[0-9a-f]{16}$ ]] || exit 2
((ssh_port>0 && ssh_port<65536)) || exit 2
[[ $(id -u) == 0 && $(id -u "$linux_user") != 0 ]] || { echo 'Use an existing ordinary Linux user.' >&2; exit 77; }
missing=()
command -v python3 >/dev/null && python3 -c 'import ssl,venv,ensurepip,sys;sys.exit(sys.version_info<(3,9))' 2>/dev/null || missing+=(python)
for binary in tmux openssl /usr/sbin/sshd ssh-keygen; do command -v "$binary" >/dev/null || missing+=("$binary"); done
if ((${#missing[@]})); then
    if command -v apt-get >/dev/null; then
        apt-get update
        DEBIAN_FRONTEND=noninteractive apt-get install -y python3 python3-venv tmux openssl openssh-server
    elif command -v dnf >/dev/null; then dnf install -y python3 python3-pip tmux openssl openssh-server openssh-clients
    else echo 'Use an Ubuntu/Debian or Fedora/RHEL WSL distribution.' >&2; exit 2; fi
fi
ssh-keygen -A
install -d -m 0755 /run/sshd
folder="/var/lib/shello-wsl/$owner"
[[ ! -L /var/lib/shello-wsl && ! -L "$folder" ]] || exit 2
install -d -m 0700 "$folder"
config="$folder/sshd_config"
if [[ -e "$config" ]] && ! head -1 "$config" | grep -qx '# Shello owned WSL SSH listener'; then
    echo 'Refusing to overwrite an unrelated SSH configuration.' >&2; exit 2
fi
cat > "$config.new" <<CONFIG
# Shello owned WSL SSH listener
Port $ssh_port
ListenAddress 0.0.0.0
PidFile $folder/sshd.pid
AuthorizedKeysFile .ssh/authorized_keys
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
PermitRootLogin no
UsePAM yes
AllowUsers $linux_user
PrintMotd no
Subsystem sftp internal-sftp
CONFIG
for key in /etc/ssh/ssh_host_{ed25519,rsa,ecdsa}_key; do [[ ! -f "$key" ]] || printf 'HostKey %s\n' "$key" >> "$config.new"; done
chmod 0600 "$config.new"
/usr/sbin/sshd -t -f "$config.new"
mv "$config.new" "$config"
python3 - "$config" "$ssh_port" <<'PY'
import json,pathlib,socket,subprocess,sys
config=pathlib.Path(sys.argv[1]);pidfile=config.parent/'sshd.pid';running=False
if pidfile.exists():
 try:
  pid=int(pidfile.read_text());running=str(config).encode() in pathlib.Path('/proc',str(pid),'cmdline').read_bytes()
 except (OSError,ValueError):pass
if not running:
 try:
  with socket.create_connection(('127.0.0.1',int(sys.argv[2])),timeout=1):
   raise SystemExit('The WSL SSH port is occupied by another service; choose a different -SshPort.')
 except OSError:pass
s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);s.connect(('192.0.2.1',9));address=s.getsockname()[0];s.close()
addresses=subprocess.check_output(['hostname','-I'],text=True).split()
routes=json.loads(subprocess.check_output(['ip','-j','-4','route','show','default'],text=True))
print(json.dumps({'config':str(config),'running':running,'address':address,'addresses':addresses,'gateway':next((r['gateway'] for r in routes if 'gateway' in r),'')}))
PY
