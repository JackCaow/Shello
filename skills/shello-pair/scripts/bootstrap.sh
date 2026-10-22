#!/usr/bin/env bash
# System preparation only; pairing itself must run as the intended ordinary user.
set -euo pipefail

prepare_path() {
    export PATH="/opt/homebrew/bin:/usr/local/bin:/opt/homebrew/opt/openssl@3/bin:/usr/local/opt/openssl@3/bin:$PATH"
}
find_binary() {
    command -v "$1" 2>/dev/null || {
        local candidate
        for candidate in "/usr/sbin/$1" "/usr/bin/$1" "/sbin/$1"; do
            if [[ -x "$candidate" ]]; then printf '%s\n' "$candidate"; return; fi
        done
        return 1
    }
}
python_ready() {
    command -v python3 >/dev/null && python3 -c 'import sys, ssl, venv, ensurepip; sys.exit(sys.version_info < (3, 9))' >/dev/null 2>&1
}
missing_dependencies() {
    python_ready || echo python
    local dependency
    for dependency in openssl tmux ssh ssh-keygen sshd; do
        find_binary "$dependency" >/dev/null || echo "$dependency"
    done
}
platform() { uname -s; }
package_manager() {
    case "$(platform)" in
        Darwin) echo brew ;;
        Linux)
            if command -v apt-get >/dev/null; then echo apt-get
            elif command -v dnf >/dev/null; then echo dnf
            else echo unsupported; fi ;;
        *) echo unsupported ;;
    esac
}
as_root() {
    if [[ $(id -u) == 0 ]]; then "$@"
    elif command -v sudo >/dev/null && sudo -n true 2>/dev/null; then sudo -n "$@"
    else
        printf 'Administrator authentication required for:' >&2
        printf ' %q' "$@" >&2
        printf '\nRun this command in your own authenticated terminal, then retry. Do not send a password to the agent.\n' >&2
        return 77
    fi
}
install_brew() {
    [[ $(id -u) != 0 ]] || { echo 'Run Homebrew as the intended ordinary user.' >&2; return 77; }
    local task_dir status=0
    task_dir=$(mktemp -d "${TMPDIR:-/tmp}/shello-brew.XXXXXX")
    if curl --fail --silent --show-error --location --proto '=https' \
        https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh -o "$task_dir/install.sh"; then
        NONINTERACTIVE=1 /bin/bash "$task_dir/install.sh" || status=$?
    else status=$?; fi
    rm -rf "$task_dir"
    if [[ $status != 0 ]]; then
        echo 'Homebrew installation needs attention; inspect the installer result (administrator authentication or Command Line Tools may be required).' >&2
        return 77
    fi
    prepare_path
}
install_missing() {
    local manager dependency package_names package_name found
    local missing=() packages=()
    while IFS= read -r dependency; do [[ -z "$dependency" ]] || missing+=("$dependency"); done < <(missing_dependencies)
    if [[ ${#missing[@]} == 0 ]]; then echo 'Dependencies already available; nothing to install.'; return; fi
    manager=$(package_manager)
    [[ "$manager" != unsupported ]] || { echo 'Unsupported package manager; use the host setup reference.' >&2; return 2; }
    if [[ "$manager" == brew ]] && ! find_binary brew >/dev/null; then install_brew || return $?; fi
    for dependency in "${missing[@]}"; do
        case "$manager:$dependency" in
            brew:python) package_names=python ;;
            brew:openssl) package_names=openssl@3 ;;
            brew:tmux) package_names=tmux ;;
            brew:*) echo "System OpenSSH component missing: $dependency. Repair macOS system tools; do not replace sshd with Homebrew." >&2; return 2 ;;
            apt-get:python) package_names='python3 python3-venv' ;;
            dnf:python) package_names='python3 python3-pip' ;;
            apt-get:ssh|apt-get:ssh-keygen|apt-get:sshd) package_names='openssh-server openssh-client' ;;
            dnf:ssh|dnf:ssh-keygen|dnf:sshd) package_names='openssh-server openssh-clients' ;;
            *) package_names=$dependency ;;
        esac
        # Package names above are fixed literals, never user-provided shell text.
        for package_name in $package_names; do
            found=false
            for dependency in "${packages[@]+${packages[@]}}"; do [[ "$dependency" != "$package_name" ]] || found=true; done
            [[ "$found" == true ]] || packages+=("$package_name")
        done
    done
    case "$manager" in
        brew) "$(find_binary brew)" install "${packages[@]}" ;;
        apt-get) as_root apt-get update && as_root apt-get install -y "${packages[@]}" ;;
        dnf) as_root dnf install -y "${packages[@]}" ;;
    esac
}
ssh_ready() {
    python_ready || return 1
    python3 - "$ssh_port" <<'PY'
import socket, sys
try:
    with socket.create_connection(('127.0.0.1', int(sys.argv[1])), timeout=2) as connection:
        connection.settimeout(2)
        if not connection.recv(1024).startswith(b'SSH-'): raise ValueError('Not an SSH banner')
except (OSError, ValueError):
    sys.exit(1)
PY
}
tmux_probe() {
    local task_dir tmux_bin first second status=0
    tmux_bin=$(find_binary tmux) || return 1
    task_dir=$(mktemp -d "${TMPDIR:-/tmp}/shello-tmux.XXXXXX")
    if env -u TMUX "$tmux_bin" -S "$task_dir/socket" -f /dev/null new-session -d -s shello-probe 'sleep 15' &&
       env -u TMUX "$tmux_bin" -S "$task_dir/socket" set-environment -t shello-probe SHELLO_PROBE kept; then
        first=$(env -u TMUX "$tmux_bin" -S "$task_dir/socket" display-message -p -t shello-probe '#{pane_pid}') || status=1
        second=$(env -u TMUX "$tmux_bin" -S "$task_dir/socket" display-message -p -t shello-probe '#{pane_pid}') || status=1
        [[ -n "$first" && "$first" == "$second" ]] || status=1
        [[ $(env -u TMUX "$tmux_bin" -S "$task_dir/socket" show-environment -t shello-probe SHELLO_PROBE) == SHELLO_PROBE=kept ]] || status=1
    else status=1; fi
    env -u TMUX "$tmux_bin" -S "$task_dir/socket" kill-server 2>/dev/null || true
    rm -rf "$task_dir"
    return "$status"
}
configure_ssh() {
    local missing
    missing=$(missing_dependencies)
    if [[ -n "$missing" ]]; then echo 'Install missing dependencies before configuring SSH.' >&2; return 2; fi
    if ssh_ready; then echo "SSH already serves port $ssh_port; preserving its service and configuration."; return; fi
    case "$(platform)" in
        Darwin) as_root /usr/sbin/systemsetup -setremotelogin on || return $? ;;
        Linux)
            as_root "$(find_binary ssh-keygen)" -A || return $?
            as_root "$(find_binary sshd)" -t || return $?
            if ! command -v systemctl >/dev/null || [[ ! -d /run/systemd/system ]]; then
                echo 'No running systemd; use the actual host init service (see host-setup.md).' >&2; return 2
            fi
            local service=sshd.service
            [[ $(package_manager) != apt-get ]] || service=ssh.service
            as_root systemctl enable --now "$service" || return $? ;;
        *) echo 'Unsupported SSH service manager.' >&2; return 2 ;;
    esac
    local attempt
    for attempt in 1 2 3 4 5; do ssh_ready && return; sleep 1; done
    echo "SSH is not serving a banner on port $ssh_port. Preserve any existing custom port/configuration and inspect the service." >&2
    return 2
}
main() {
    local mode=${1:---check} dependency failed=0
    ssh_port=22
    [[ $# == 0 ]] || shift
    while [[ $# -gt 0 ]]; do
        case "$1" in
            --ssh-port) [[ $# -ge 2 ]] || return 2; ssh_port=$2; shift 2 ;;
            *) echo 'Usage: bootstrap.sh --check|--install|--configure-ssh [--ssh-port PORT]' >&2; return 2 ;;
        esac
    done
    [[ "$ssh_port" =~ ^[0-9]+$ && ${#ssh_port} -le 5 ]] && ((10#$ssh_port >= 1 && 10#$ssh_port <= 65535)) || { echo 'Invalid SSH port.' >&2; return 2; }
    ssh_port=$((10#$ssh_port))
    prepare_path
    if [[ $(id -u) == 0 ]]; then echo 'Use the intended ordinary SSH account, not root.' >&2; return 77; fi
    case "$mode" in
        --install) install_missing || return $? ;;
        --configure-ssh) configure_ssh || return $? ;;
        --check) ;;
        *) echo 'Usage: bootstrap.sh --check|--install|--configure-ssh [--ssh-port PORT]' >&2; return 2 ;;
    esac
    while IFS= read -r dependency; do [[ -z "$dependency" ]] || { echo "MISSING=$dependency"; failed=2; }; done < <(missing_dependencies)
    if ssh_ready; then echo "SSH_READY=true PORT=$ssh_port"; else echo "SSH_READY=false PORT=$ssh_port"; failed=2; fi
    if tmux_probe; then echo 'TMUX_READY=true'; else echo 'TMUX_READY=false'; failed=2; fi
    return "$failed"
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then main "$@"; fi
