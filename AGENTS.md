# Mobile Terminal 安卓原生终端

## 项目目标

在 ARM64 Android 10+ 手机上通过 tawcroot 运行 Debian 和交互式 Bash。首版提供原生界面、Warp 命令块、多标签、中文输入、全屏终端和文件交换。Warp 账号与 AI 属于后续版本。

## 开发入口

- `./scripts/doctor` 检查 Linux 构建工具、Android SDK 和设备。
- `./scripts/build-debug` 获取锁定依赖并生成调试签名 APK。
- `./scripts/test-host` 验证可在主机运行的测试。
- `./scripts/test-device` 在已连接的 ARM64 设备上安装调试 APK 并执行设备测试。
- 用 [项目计划](docs/project-plan.md) 记录验收条件，用 [构建说明](README.md) 记录实际命令和构建状态。

## 上游与会话

- 在 `upstream/revisions.env` 固定完整提交号。记录本地补丁的原因和测试。更新上游时同步检查许可证、构建和运行兼容性。
- 每个 Bash 标签独占 Bash 和 PTY。通过 Warp 上游事件识别命令边界。修改帧协议时同时更新 Kotlin、Rust 与跨端用例。
- 图形标签按 TAWC 主窗口绑定显示表面。切换标签或重建 Activity 时只解绑 surface。关闭标签须等待原生窗口结束，保留应用的保存确认流程。
- 使用应用私有目录持久化用户主目录。安装、重试和升级均保留该目录。文件交换使用用户选定的文件。
- 运行时日志只记录诊断元数据。测试使用专用目录。

## 验证

- 修改解析器时回放分片字节流。修改安装器时检查中断、校验失败与重试。修改 PTY、输入法或会话生命周期时执行设备测试。
- 明确记录主机、模拟器与 ARM64 真机结果。交付记录包含 APK 哈希、实测机型、Android 版本和仍待确认的项目。
