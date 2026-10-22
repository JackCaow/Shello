---
name: shello-pair
description: Prepare a macOS, Linux or Windows (WSL2) computer for Shello Android pairing. Check and install missing dependencies, enable SSH, verify tmux, start the temporary pairing service and display its QR code. Use when the user asks to pair or configure a computer for Shello.
---

# Shello 配对

把**运行 agent 的这台电脑**配置成 Shello 的 SSH 主机，展示可扫描的二维码，并验证手机连接。支持 macOS、Debian/Ubuntu、Fedora/RHEL，以及 Windows 11 + WSL2。Windows 连接到 WSL Linux，不能启动原生 PowerShell 终端；其他系统先查适用的官方安装方式。

## 先选择执行平台

确认目标电脑的系统。**Windows 从普通用户的 PowerShell 运行随 skill 附带的 `scripts/pair.ps1`**，不要先执行下方 Unix bootstrap，也不要把整个脚本交给管理员账户：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\path\to\shello-pair\scripts\pair.ps1 -Check
powershell.exe -NoProfile -ExecutionPolicy Bypass -File C:\path\to\shello-pair\scripts\pair.ps1 -Json
```

`-Check` 只检查 WSL2、普通 Linux 用户和可用 Windows 地址；返回 2 表示缺少环境。正式运行会补依赖、配置独立的 WSL SSH 监听并启动配对。`ready` 事件的 `qr_image` 是 Windows 本地 PNG：保持工具运行，立刻在对话展示。没有图片能力时去掉 `-Json` 显示文本二维码。Windows 启动器已包含经过仓库版本管理的全部辅助脚本，复制整个 skill 后也可用，无需运行可变远程脚本。详见 [Windows / WSL2](references/windows-wsl.md)，完成后直接进入本文“完成验收”。

macOS/Linux 才执行以下流程。

## 准备电脑

- 先确认执行环境就是用户要连接的电脑，而非 agent 的容器、云沙箱或另一台 SSH 主机。明确指定其他电脑时，在该电脑执行；不要在错误的宿主机启用 SSH。
- 用户要求配置配对，即授权安装必要依赖并开启 SSH。已有授权时直接推进，不再逐项确认。当前任务如果仅是咨询或检查，则只执行 `--check`。
- 全程使用目标普通用户；**不要用 sudo 运行整个 skill 或配对脚本**，否则公钥会写进 root 账户。只有系统安装、开启 SSH 的子命令需要管理员权限。沿用现有 SSH 端口和用户限制，不覆盖密钥、`authorized_keys`、`sshd_config`、tmux 配置或现有会话，不开启 root 登录，不关闭防火墙。

以下路径以本 `SKILL.md` 所在目录为基准，命令中替换为实际绝对路径：

```sh
bash /path/to/shello-pair/scripts/bootstrap.sh --check
bash /path/to/shello-pair/scripts/bootstrap.sh --install
bash /path/to/shello-pair/scripts/bootstrap.sh --configure-ssh --ssh-port 22
```

`--check` 返回 2 表示依赖或 SSH 尚未就绪，属于可修复状态；安装只补缺项，检查已通过时可跳过安装。默认 SSH 端口 22；已有自定义端口就将三步的 `--ssh-port` 都改成该端口。安装后再检查一次。

读 [主机配置](references/host-setup.md) 处理管理员认证、macOS 远程登录限制、无 systemd 环境及网络问题。退出码 77 表示需要系统认证或权限：告知用户具体命令/系统设置，让用户在自己的系统界面完成认证，随后重试；不要索取密码，不修改权限机制。已有 SSH 服务运行时不重启它。

tmux 只验证独立临时 socket 上的后台会话和跨客户端状态保留，完成即清理；不会操作用户现有 tmux server。

## 启动并显示二维码

在**可持续运行的终端或工具会话**里启动，不用短命后台 shell，也不把脚本整体交给 sudo：

```sh
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
python3 /path/to/shello-pair/scripts/start_pairing.py --network auto --ssh-port 22 --ttl 300
```

- 仓库内复用 `companion/pair.sh`；复制出去的独立 skill 自动下载并校验固定 v0.1.1 launcher。只下载官方地址，SHA-256 不匹配就停止；不要忽略校验。可用 `--launcher /absolute/path/pair.sh` 明确指定用户信任的本地版本。
- `auto` 优先已连接的 Tailscale，否则局域网；可用 `--network lan|tailscale` 或 `--host <电脑的 IPv4>`。不要为配对自动加入 tailnet、切换账号或设置路由器端口转发。
- 工具会先校验服务证书 pin，然后输出 JSON `ready` 事件，包含 `qr_image`、`expires_at_unix`。**立刻读取并在当前对话内展示该本地 PNG**，提示手机打开 **Shello → + → 扫码轻松配对**。支持图片时不能只贴文件路径；没有图片能力的终端可在另一个调用里运行事件提供的 `renderer_python` 与 `render_qr.py <qr_payload_file> <qr_image> --ascii` 显示文本二维码。
- 服务保持运行时才能配对。等待期间用短时轮询读取进展，避免阻塞整个对话；二维码失效则告诉用户并按需要重新启动。会话终止、过期或中断时会清理临时二维码、payload 和日志。不要把这些文件提交、上传到图床、发送第三方解码服务或保存进 README；也不要打印原始 token。

## 完成验收

`public_key_installed` 只表示手机公钥已追加，不等于 SSH/tmux 已连通。让手机完成确认和 SSH 验证、进入一个终端；有手机操作工具且用户授权时直接操作，否则请用户确认连接结果。可在新建的测试会话里执行 `printf 'Shello connected\n'`，退出重开确认 tmux 能恢复，勿改动已有业务会话。

只有确认手机 SSH 连通后才报告“配对成功”；否则准确报告“公钥已安装，等待手机验证”。停止临时服务不会删除已安装的手机公钥，不要在收尾时撤销配对。简要报告已安装的依赖、SSH 状态、网络选择和手机验证结果。
