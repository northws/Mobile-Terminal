# Mobile Terminal

Mobile Terminal 是一款 Android 原生终端。它在手机本地运行 Debian Bash，从 Warp 上游获取命令块边界，并使用 Termux 提供的 VT 终端渲染全屏程序。

## 使用

在 ARM64 Android 10 或更新版本上侧载调试 APK。首次打开后选择安装 Debian，应用会校验 Debian 镜像、安装 `vim` 和 `less` 并检查终端。初始化需要网络及至少 600 MB 可用空间。完成以后可离线打开 Bash。

- **＋ 新标签 → Bash**打开独立的终端。所有标签共享 `/home/warp`。
- **＋ 新标签 → OpenCode / Vim**打开独立的终端应用标签。用**其他终端应用**启动 Debian 中已安装的其他命令和参数。从菜单打开的程序退出后，可在同一个标签继续用 Bash。
- 从 Bash 运行进入备用屏幕的程序时，应用会另开一个标签显示它。普通输出仍显示在原 Bash。终端应用顶部提供「键盘」和「关闭标签」，底部保留终端快捷键。Bash 打开的应用标签可以暂时关闭，再从原 Bash 点「打开终端应用」继续。程序退出后，原 Bash 显示退出状态。
- **＋ 新标签 → 图形应用**列出 Debian 中已安装的桌面程序。可在联网时点「安装示例应用」，安装 Mousepad、`xeyes` 和中文字体。也可在 Bash 执行 `mousepad`、`xeyes` 或 `xeyes &`。新窗口会出现在 Warp 的标签栏。安装完成以后可以离线运行。
- 图形标签中的「键盘」手动打开 Android 输入法。「关闭标签」请求程序关闭窗口。若应用有未保存内容，可在其对话框中保存或取消。
- **运行**执行输入框中的命令。APT 等程序等待确认时，在「进程输入」中输入回复再点「发送」。留空发送等同按回车。命令块可以复制命令或输出。
- 普通进度更新会覆盖当前行。APT 的安装进度在命令输出下方单独刷新，解包和配置日志保留在命令块中。命令块默认跟随最新输出，回看旧内容时会暂停跟随，点「回到最新」可恢复。
- 「图形应用 → 图形渲染」默认使用「自动」。Adreno 设备会分别检测 Wayland、X11 和已安装的 Chromium，列表显示实际渲染器或回退原因。「软件」会让新启动的程序使用软件渲染。已有窗口保留运行状态。旧版 Debian 若缺少图形依赖，可联网安装示例应用后点「重新检测」。
- 新建 Bash 会继承检测通过的 Wayland EGL/GLES 环境。X11 加速可用时，可在 Bash 运行 `/usr/lib/hybris/warp-x11-egl 程序名`。如果 Wayland 加速也已启用，可以用 `HYBRIS_EGLPLATFORM=x11 程序名`。通过检测后可以离线运行。
- Debian 已安装 Chromium 时，从「图形应用 → Chromium → 兼容启动」进入。兼容启动按原生 Wayland 硬件合成、XWayland 硬件合成、保留 WebGL 加速的软件合成的顺序选择检测通过的路径。可打开 `chrome://gpu` 查看合成状态，再打开 `file:///usr/lib/hybris/webgl.html` 查看两种 WebGL API 的像素读回。检测完成后可离线启动。
- 原生 Wayland 硬件合成使用 Chromium 进程内的 GPU 线程。确认框可选用已通过检测的「XWayland 硬件合成」或「软件合成（保留 WebGL 加速）」。若只有 WebGL 2 通过检测，确认框提供「WebGL 2 硬件启动」。还可选择「软件启动」关闭 GPU 加速。切换模式前先关闭已有 Chromium 窗口。兼容模式使用独立配置并关闭 Chromium 的沙箱，请只打开可信页面。
- Bash 页的**终端**可手动打开 VT 网格。运行中的程序也可用此按钮打开终端应用标签。点按网格可以使用输入法。
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
    gettext libexpat1-dev libxml2-dev xsltproc xz-utils \
    patchelf libvulkan-dev libwayland-dev libwayland-bin wayland-protocols \
    libx11-xcb-dev libegl-dev:arm64 libgles-dev:arm64 libwayland-dev:arm64 libx11-dev:arm64
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
