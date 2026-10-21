# ghostty-android

实验性的 Android 原生终端库：Ghostty VT 内核、Canvas 绘制、触摸选择和中文 IME 输入。非 Ghostty 官方项目；源码仓库为 [JackCaow/ghostty-android](https://github.com/JackCaow/ghostty-android)，尚未发布 Maven 包。

- `TerminalSession`：输出字节流、按键编码、查询应答、历史和内存快照。
- `TerminalView`：原生显示、长按选择、双手柄、边缘自动滚动。
- `TerminalInputView`：可选输入控件；拼音候选保留在本地，确认后发送 UTF-8。
- `sample`：参考 Moshi 的深色圆角终端、底部快捷栏、可拖动/锁定方向浮板、草稿与直接输入切换、选择复制及最小化会话卡片。默认离线回显，也可通过 USB 连接 Mac 上的临时 OpenSSH 服务，执行真实 shell；加 `--tmux` 后，网络中断会自动重试并挂接原来的 shell/vim 会话，主动断开则停止重试。SSH 依赖仅在 sample 中，库本身不依赖网络；UI 与 Moshi 的视觉差距仍在调整。

最低构建版本为 Android 8.1（API 27），目前仅提供 ARM64。已完成 API 27 ARM64 模拟器的中文输入、复杂 emoji 选择和滚动手柄测试；此前另有 API 36 真机回归。库 API 仍可能变化。SSH/WebSocket、鉴权、流控和断线恢复协议由使用方实现。库不包含 DimRemote 的业务代码或实验 HTTP 服务。

完整构建命令和接入示例见 [README](README.md)，资源与线程约定见 [API](docs/API.md)。采用 MIT，保留来源版权和依赖声明；当前仅公开源码；CI 中的 Maven Local 用于 AAR 接入测试，不上传远程 Maven 仓库。

[验证记录](docs/VALIDATION.md)区分新库验收和历史原型证据。字符簇边界使用固定版本的 Ghostty 规则，避免旧 Android ICU 拆分 emoji；继续保留程序的列坐标，支持应用主动开启 mode 2027。旧系统缺少的新 emoji 字形不会因此补齐，不承诺所有字体、TUI 和输入法完全兼容。

[真机局域网测试与厂商省电限制](docs/DEVICE_RECOVERY.md)

示例中点击发送会附加回车，长按仅发送文字；粘贴先进入本地草稿。离线模式的控制键显示按键反馈，SSH 模式控制真实 PTY；最小化保留本次会话和草稿，SSH 模式由带常驻通知的前台服务持有连接和终端状态，切后台、普通锁屏及 Activity 重建时继续保留；主动断开会停止服务保活。App 进程死亡后，普通打开会自动挂接此前成功连接的 tmux 会话；主动断开或配置/密钥变化会取消恢复。拒绝通知权限不影响 SSH，自动恢复不再请求授权；系统拒绝前台服务启动时会提示重试。[界面截图与验证](docs/VALIDATION.md#moshi-inspired-sample-ui)。[本机 SSH 操作说明](docs/LOCAL_SSH.md)。
