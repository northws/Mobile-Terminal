# 上游来源

完整提交号见 `revisions.env`。Android 应用的构建输出中包含上游清单和随包组件的许可证。构建过程将应用的 Rust 后端加入 Warp 的本地构建缓存，并从锁定提交读取 Bash 启动资源。

| 来源 | 使用范围 | 许可证 |
|---|---|---|
| [Warp](https://github.com/warpdotdev/warp) | `warp_terminal`、终端依赖和 Bash 资源 | AGPL-3.0，`warpui_core` 与 `warpui` 使用 MIT |
| [TAWC](https://github.com/wmww/tawc) | `tawcroot`、Wayland 合成器、输入法桥接、Android XWayland 构建 | TAWC 自有代码使用 MIT，原生依赖按上游 `deps/deps.list` 锁定 |
| [Termux](https://github.com/termux/termux-app) | `terminal-emulator` 与 `TerminalRenderer` | Apache-2.0 |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | Debian tar 归档读取 | Apache-2.0 |
| [Debian 镜像源码](https://github.com/debuerreotype/docker-debian-artifacts) | 首次安装所用 Debian 13 ARM64 基础文件 | Debian 各软件包的许可证见 guest 的 `/usr/share/doc` |

Android 首次安装下载的 Debian 镜像必须与锁定的 SHA-256 一致。基础软件的安装通过 Debian 软件包管理器验证软件包签名。

- `tawc-tabs.patch` 在锁定版合成器上添加按主窗口关闭的 JNI 入口，并将 Warp 独立主目录纳入桌面应用扫描。通过原生 ARM64 编译、两种协议的窗口启动以及取消关闭场景验收。
- `tawc-libtool.patch` 适配新版 Libtool 移除的宏。Bionic 使用 ELF 的无前缀 C 符号。通过 XWayland 全量交叉编译验证。
