# Android 与 Linux 后端通信

每个标签启动独立的 tawcroot 进程及 Warp 后端。通过标准输入与标准输出通信，标准错误用于进程诊断。Bash 及其子进程使用后端创建的 PTY。

每条消息由四字节无符号大端长度、一字节类型和消息体构成。长度包含类型字节，取值为 1 到 262144。类型 0 为 UTF-8 JSON，类型 1 为原始 PTY 字节。接收端按长度读取，允许消息拆成多次读取。

| 方向 | 类型 | 内容 |
|---|---|---|
| 后端 → Android | `hello` | `version` 等于 2，且为第一条消息。可选的 `directory_fallback` 为 `true` 时表示快捷方式的工作目录不可用，Bash 已改从主目录启动 |
| 后端 → Android | `prompt` | `directory` 是下一条命令的工作目录，Bash 完成提示符绘制后发送 |
| 后端 → Android | `started` | `command` 来自 Warp Bash preexec 事件 |
| 后端 → Android | `output` | `text`、`foreground`（可空的 RGB 整数）、`bold` |
| 后端 → Android | `line_edit` | `operation` 为 `column`（从 0 起的 `column` 字段）、`backspace`、`clear_right`、`clear_left` 或 `clear_all`，只影响命令块当前行 |
| 后端 → Android | `status_line` | `text` 是当前命令独立状态行的快照，`null` 表示清除。它不写入命令块或复制输出 |
| 后端 → Android | `finished` | `code` 为交互命令退出码 |
| 后端 → Android | `screen` | `alternate` 更新全屏终端状态 |
| 后端 → Android | `exit` | `code` 为 shell 的退出状态 |
| Android → 后端 | `resize` | `rows`、`columns` 用于 PTY 窗口大小 |
| Android → 后端 | `close` | 当前标签请求关闭会话 |
| 双向 | 原始字节 | 后端发送 PTY 原始输出以绘制终端，Android 发送键入、粘贴、终端回复 |

Warp 解析器读取整条 PTY 输出流并验证 Bash 会话标识。复制命令时优先使用 Android 编辑框提交的完整内容，保留多行、管道和引用。命令块中保留前景色与粗体，终端网格通过 Termux 渲染光标与备用屏幕。标准输入关闭或收到 `close` 时请求结束当前 Bash 会话。

命令块中的回车将写入位置移到当前行开头，换行结束当前行。当前行刷新会保留先前已完成的输出行。APT 等程序设置滚动区域并保留最后一行时，对该行的绘制显示为一条临时状态，光标恢复后继续记录正常输出。完整的 VT 光标和屏幕操作由终端网格处理。
