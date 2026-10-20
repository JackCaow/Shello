# ghostty-android

实验性的 Android 原生终端库：Ghostty VT 内核、Canvas 绘制、触摸选择和中文 IME 输入。非 Ghostty 官方项目；源码仓库为 [JackCaow/ghostty-android](https://github.com/JackCaow/ghostty-android)，尚未发布 Maven 包。

- `TerminalSession`：输出字节流、按键编码、查询应答、历史和内存快照。
- `TerminalView`：原生显示、长按选择、双手柄、边缘自动滚动。
- `TerminalInputView`：可选输入控件；拼音候选保留在本地，确认后发送 UTF-8。
- `sample`：无网络权限的离线回显示例，不提供真实 shell。

最低构建版本为 Android 8.1（API 27），目前仅提供 ARM64。真机回归覆盖 API 36，API 27 的运行、输入法和字体表现仍待补测，库 API 仍可能变化。SSH/WebSocket、鉴权、流控和断线恢复协议由使用方实现。库不包含 DimRemote 的业务代码或实验 HTTP 服务。

完整构建命令和接入示例见 [README](README.md)，资源与线程约定见 [API](docs/API.md)。采用 MIT，保留来源版权和依赖声明；当前仅公开源码；CI 中的 Maven Local 用于 AAR 接入测试，不上传远程 Maven 仓库。

[验证记录](docs/VALIDATION.md)区分新库验收和历史原型证据。复杂 emoji 继续保留程序的列坐标，支持应用主动开启 mode 2027；不承诺所有字体、TUI 和输入法完全兼容。
