# Windows / WSL2 配对

支持 Windows 11、Windows PowerShell 5.1 / PowerShell 7 和 WSL2 的 Ubuntu/Debian、Fedora/RHEL。连接目标是普通 WSL Linux 用户；tmux 和智能体 CLI 在该发行版内运行，不能把原生 Windows PowerShell 会话接到 tmux。

## 执行

从目标用户的普通 PowerShell 运行 `scripts/pair.ps1 -Check`，再运行 `scripts/pair.ps1 -Json`。ExecutionPolicy Bypass 只作用于该子进程，不修改系统执行策略。不能绕过企业策略或索取管理员密码。

- 缺 WSL 组件时通过 Windows UAC 请求安装；需要重启时返回 `restart_required` / 77。让用户完成重启后重跑，不自动重启。
- 缺所选发行版时执行 `wsl --install --distribution Ubuntu --no-launch`，返回 `initialization_required` / 77。让用户打开 `wsl -d Ubuntu` 完成普通 Linux 用户初始化后重跑；不代设密码，不使用 root 作为配对用户。
- 已有 WSL1 时停止；不转换发行版、不修改默认发行版、不执行 `wsl --shutdown`。可用 `-Distribution Debian` 显式选择已初始化的 WSL2 发行版。
- 在所选 WSL 内通过 apt/dnf 补 Python 3.9+、venv/ensurepip、OpenSSL、tmux 和 OpenSSH。只为缺失的 host key 生成文件；保留正常 SSH 服务和配置，在 `/var/lib/shello-wsl/<owner>/sshd_config` 创建独立公钥监听，默认 SSH **2222**、临时 HTTPS **8765**。
- 机器网络配置才通过 UAC 子进程提升权限；发行版选择、普通用户配对及公钥追加始终在原 Windows 用户的 WSL 环境完成。

## 网络与生命周期

`-Network auto|lan|tailscale`，auto 优先 Windows 的 Tailscale IPv4；`-HostAddress` 必须是本 Windows 主机已分配的私网 IPv4。不要加入 tailnet、开放路由器端口或禁用防火墙。

WSL NAT 模式创建绑定该 Windows 地址的 `netsh portproxy`，以私有归属状态更新自己创建的 SSH 映射；遇到不属于 Shello 的端口/规则立即停止。镜像模式在所选地址确实存在于 WSL 时直连。Windows 防火墙仅放行所选 LAN 子网或 Tailscale 段的两个 TCP 端口；支持 Hyper-V 防火墙的系统同时配置对应来源。WSL 自己的 UFW/nftables 及 tailnet ACL 仍需允许这些端口，不能用关闭防火墙的方式修复。

转发后的 HTTPS 证书 pin 在展示二维码前复验；手机随后验证返回的 WSL SSH 指纹、Linux 用户及 SSH 端口。退出、过期或取消时清理本次 HTTPS 转发和临时规则。即使前端意外结束，网络子进程也在 TTL + 180 秒后清理临时映射；WSL 配对进程有自身期限。SSH 监听和 SSH 映射保留，非秘密日志在 `%LOCALAPPDATA%\Shello\network`。二维码和载荷在普通用户私有目录，不能提交或分享。

Windows/WSL 重启、网络地址变化后重新运行脚本刷新所选地址的映射。需要改变端口时使用 `-SshPort 2223 -PairPort 8766`；既有保存连接仍指向原端口，不会自动迁移。SSH 进程保持所选 WSL 活跃，但不保证跨 Windows 重启自动启动。关闭仍使用的 WSL/tmux 会丢失远端进程。

## 验收边界

`-Check` 成功不代表依赖已安装或手机可达；`public_key_installed` 不代表手机已连通。按 SKILL.md 的“完成验收”在手机验证 SSH 与 tmux 恢复。

CI 检查 Windows PowerShell 5.1 / PowerShell 7 参数、网络选择、归属、清理和故障路径，系统安装及网络变更使用 mock；Linux CI 另测真实 HTTPS 转发和独立 SSH/tmux。没有 Windows 真机时，不得报告真实 Windows + WSL2 + 手机端到端已验收。

参考：[WSL 命令](https://learn.microsoft.com/windows/wsl/basic-commands)、[WSL 网络](https://learn.microsoft.com/windows/wsl/networking)。
