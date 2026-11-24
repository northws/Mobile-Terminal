# Mobile Terminal

Mobile Terminal 是一款 Android 原生终端。它在手机本地运行 Debian Bash，从 Warp 上游获取命令块边界，并使用 Termux 提供的 VT 终端渲染全屏程序。

## 使用

在 ARM64 Android 10 或更新版本上侧载调试 APK。首次打开后选择安装 Debian，应用会校验 Debian 镜像、安装 `vim` 和 `less` 并检查终端。初始化需要网络及至少 600 MB 可用空间。完成以后可离线打开 Bash。

- **＋ 新标签 → Bash**打开独立的终端。所有标签共享 `/home/warp`。
- **＋ 新标签 → 图形应用**列出 Debian 中已安装的桌面程序。可在联网时点「安装示例应用」，安装 Mousepad、`xeyes` 和中文字体。也可在 Bash 执行 `mousepad`、`xeyes` 或 `xeyes &`。新窗口会出现在 Warp 的标签栏。安装完成以后可以离线运行。
- 图形标签中的「键盘」手动打开 Android 输入法。「关闭标签」请求程序关闭窗口。若应用有未保存内容，可在其对话框中保存或取消。
- **运行**执行输入框中的命令。APT 等程序等待确认时，在「进程输入」中输入回复再点「发送」。留空发送等同按回车。命令块可以复制命令或输出。
- Debian 已安装 Chromium 时，可从「图形应用 → Chromium → 兼容启动」打开。兼容模式使用软件渲染和独立配置，并关闭 Chromium 自身的沙箱。请只打开可信页面。
- **终端**切换到 VT 网格，以便向需要交互输入的进程发送按键。程序进入备用屏幕时自动显示网格。点按网格可以使用输入法。
- **文件**可导入至 `~/Downloads`，或从主目录导出至系统文件选择器指定的位置。

## Linux 构建

构建主机采用 Linux x86_64。Windows 可使用 WSL2。Debian 13 主机所需的原生包和 ARM64 库如下。

```sh
sudo dpkg --add-architecture arm64
sudo apt-get update
sudo apt-get install git curl unzip file make build-essential pkg-config python3 \
    openjdk-21-jdk-headless g++-aarch64-linux-gnu libc6-dev-arm64-cross \
    libfontconfig-dev libfontconfig-dev:arm64 libssl-dev libssl-dev:arm64 \
    meson ninja-build autoconf automake libtool libtool-bin bison flex gperf \
    gettext libexpat1-dev libxml2-dev xsltproc xz-utils
```

通过 rustup 安装 Rust 1.94.0 及 `aarch64-unknown-linux-gnu`、`aarch64-linux-android` 目标。通过 Android SDK Manager 安装 `platforms;android-36`、`build-tools;36.0.0`、`ndk;27.2.12479018` 和 `platform-tools`。将 `ANDROID_SDK_ROOT` 指向 SDK。TAWC 的构建包装器使用 Gradle 8.12，已有同版本 Gradle 时可设置 `WARP_GRADLE` 为其绝对路径。

```sh
bash scripts/doctor
bash scripts/build-debug
bash scripts/test-host
bash scripts/test-device
```

APK 和 SHA-256 默认位于 `build/mobile-terminal-arm64-debug.apk` 和 `build/mobile-terminal-arm64-debug.apk.sha256`。只运行 Rust 和真实 Bash 主机测试时使用 `bash scripts/test-host --backend-only`。设备测试需要授权的 USB 或无线 ADB 设备。多台设备同时连接时可使用 `ANDROID_SERIAL` 指定目标。

工程需要联网获取锁定的上游提交、Gradle 依赖和 Cargo 依赖。上游缓存默认在 `.upstream/`，通过 `WARP_UPSTREAM_DIR` 可指定 Linux 文件系统上的专用缓存。构建产物目录可通过 `WARP_BUILD_DIR` 指定。

## 工程资料

- [项目计划](docs/project-plan.md)
- [终端协议](docs/protocol.md)
- [上游与许可证](upstream/README.md)
- [验收记录](docs/verification.md)
