# 主机配置与故障处理

## 支持范围与权限

Unix bootstrap 支持 macOS/Homebrew、Debian/Ubuntu/apt-get、Fedora/RHEL/dnf。Windows 请使用 [WSL2 配对](windows-wsl.md)，不要在 Windows 运行 Unix bootstrap。先运行 `bootstrap.sh --check`；同一台主机重复执行不会主动升级已满足条件的软件，不会改用户 shell/tmux 配置。

Python 需要 3.9+，且 `ssl`、`venv`、`ensurepip` 可用。qrcode==8.2 由原有 launcher 安装在普通用户私有 venv，图片渲染只用 qrcode 与 Python 标准库，不需要 Pillow。OpenSSL 用于临时 HTTPS 证书；tmux 用于远端会话保留。

脚本仅使用可用的免交互系统权限。若退出 77，在用户自己的终端执行 `sudo -v` 或相应系统设置；不要求用户在聊天里发送密码。凭据缓存通常只作用于该终端，agent 的另一个进程未必能复用；这时让用户在已认证终端执行报出的具体配置命令，再由 agent 检查。不要反复重试同一个权限失败。

## macOS

缺少 Homebrew 时，安装助手下载官方安装器并以 `NONINTERACTIVE=1` 运行。需要管理员认证或 Xcode Command Line Tools 的系统弹窗时，让用户完成该系统操作；不执行未经用户选择的镜像或第三方安装器。Homebrew 公式安装在目标普通用户下执行。

SSH 使用系统自带 OpenSSH。已有服务正常应直接复用；未开启时执行：

```sh
sudo /usr/sbin/systemsetup -setremotelogin on
```

如果 systemsetup 报需要 Full Disk Access，优先让用户打开 **系统设置 → 通用 → 共享 → 远程登录**。选择允许当前目标用户登录，保留其他已允许用户。不要为此开启“远程用户完全磁盘访问”，也不要用命令绕过 TCC。若需要 agent 所在终端的系统授权，由用户在隐私设置中处理。

若受限用户组拒绝当前用户，先读取“远程登录”的允许用户列表，按用户指定账户添加；不要把已有“仅这些用户”改成“所有用户”。

参考：[Apple 远程登录](https://support.apple.com/guide/mac-help/allow-a-remote-computer-to-access-your-mac-mchlp1066/mac)、[Homebrew 安装与无人值守模式](https://docs.brew.sh/Installation)。

## Linux

缺少依赖时只通过发行版包管理器安装。Ubuntu/Debian 使用 `python3 python3-venv openssl tmux openssh-server openssh-client` 中的缺项；Fedora/RHEL 使用 `python3 python3-pip openssl tmux openssh-server openssh-clients` 中的缺项。

SSH 已在给定端口提供 banner 则不动服务。未运行时，仅创建缺失的系统 host keys（`ssh-keygen -A`）、校验配置（`sshd -t`），随后启动并启用 `ssh.service` 或 `sshd.service`。Ubuntu 的 socket activation 可以共存；不要为了 Shello 禁用现有 socket/service。已有自定义端口必须沿用，不强行改成 22。

脚本不会覆盖配置来修复 `sshd -t` 失败。查看具体错误；确需修改时先保留原文件，做最小调整、复验后再 reload。不要关闭公钥认证限制、启用 root 登录或关闭现有多因素认证。若额外认证导致本协议无法登录，应报告兼容性限制。

无 systemd 的环境由 agent 识别实际 init（例如已有的 `service ssh start`）；容器里只有 loopback 可达不代表手机可连接。不要直接在容器内冒充宿主机启动服务，也不要修改宿主机挂载配置。

参考：[Ubuntu OpenSSH 服务](https://documentation.ubuntu.com/server/how-to/security/openssh-server/)。

## 网络与二维码

先区分 LAN 与 Tailscale。两端已经连接同一 tailnet 时使用 auto/tailscale；只有 CLI 检测不到但用户已提供 Tailscale 地址时可显式指定 `--host`。不要猜另一台机器的地址。

本机 SSH banner、tmux 后台会话状态、二维码证书 pin 三项通过后，再让手机验证。连接失败时检查 Wi-Fi 隔离、VPN 规则和防火墙；本机 loopback 成功不能证明手机可达。

需要固定临时 HTTPS 端口时传 `start_pairing.py --port 8765`。只有确认防火墙在拦截且已知手机来源 IP/网络时，才添加仅针对该来源和所需端口的规则，记录改动；临时 HTTPS 规则在结束时删除。不要关闭整个防火墙或向互联网暴露端口。保留用户已有 SSH 规则。

二维码默认 300 秒有效，服务在收到公钥后保留约 30 秒以处理同一手机重试。二维码中的 token、证书 pin 和手机确认的 SSH host keys 是不同的校验环节；不要关闭任一校验来“连通”。
