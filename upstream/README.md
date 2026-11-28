# 上游来源

完整提交号见 `revisions.env`。Android 应用的构建输出中包含上游清单和随包组件的许可证。构建过程将应用的 Rust 后端加入 Warp 的本地构建缓存，并从锁定提交读取 Bash 启动资源。

| 来源 | 使用范围 | 许可证 |
|---|---|---|
| [Warp](https://github.com/warpdotdev/warp) | `warp_terminal`、终端依赖和 Bash 资源 | AGPL-3.0，`warpui_core` 与 `warpui` 使用 MIT |
| [TAWC](https://github.com/wmww/tawc) | `tawcroot`、Wayland 合成器、输入法桥接、Android XWayland 构建 | TAWC 自有代码使用 MIT，原生依赖按上游 `deps/deps.list` 锁定 |
| [TAWC 的 libhybris 分支](https://github.com/wmww/libhybris) | ARM64 Debian EGL、GLES 和图形平台桥接，提交 `ac17d9e228788f7da745609b7a6362d6242bbd71` 由 TAWC 清单锁定 | 随包附上游 Apache、BSD、GPL、ISC、LGPL 和 MIT 许可证 |
| [Termux](https://github.com/termux/termux-app) | `terminal-emulator` 与 `TerminalRenderer` | Apache-2.0 |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | Debian tar 归档读取 | Apache-2.0 |
| [Debian 镜像源码](https://github.com/debuerreotype/docker-debian-artifacts) | 首次安装所用 Debian 13 ARM64 基础文件 | Debian 各软件包的许可证见 guest 的 `/usr/share/doc` |

Android 首次安装下载的 Debian 镜像必须与锁定的 SHA-256 一致。基础软件的安装通过 Debian 软件包管理器验证软件包签名。

- `tawc-debian-sudo.patch` 让 Debian 自带的 `/usr/bin/sudo` 在 tawcroot 中获得虚拟 root。执行时核对 ELF 的实际位置和 setuid 位，初始化辅助向量沿用虚拟用户 ID 并启用 glibc 的安全执行环境。Android 进程权限仍由应用 UID 决定。验证覆盖 `id -un`、`sudo -n id -u`、APT 和会话返回后的身份。
- `tawc-tabs.patch` 在锁定版合成器上添加按主窗口关闭的 JNI 入口，并将 Warp 独立主目录纳入桌面应用扫描。通过原生 ARM64 编译、两种协议的窗口启动以及取消关闭场景验收。
- `tawc-window-origin.patch` 将 Wayland 客户端凭据或 X11 窗口提供的 PID 随窗口元数据传给 Android。创建快捷方式时，应用用它查找 Debian 内的工作目录，供从 Bash 打开时使用。通过 ARM64 合成器构建及图形窗口启动验证。
- `tawc-libtool.patch` 适配新版 Libtool 移除的宏。Bionic 使用 ELF 的无前缀 C 符号。通过 XWayland 全量交叉编译验证。
- `tawc-libhybris.patch` 让 Debian 构建复用 XWayland 依赖中锁定的私有 Wayland EGL 头文件，并在头文件路径变动时刷新配置。通过 ARM64 交叉编译验证。
- `libhybris-chromium-egl.patch` 为 Chromium 的硬件探测和启动提供 Android 离屏 EGL 显示。ANGLE 会在缺少 DRM render node 时传入空的 GBM 设备句柄。补丁仅在 Chromium 启动器启用此适配时接受该请求，并通过 `eglGetProcAddress` 返回桥接库的扩展查询和签名匹配的 EXT 入口。
- `libhybris-chromium-x11-compositing.patch` 让 ANGLE 通过 libhybris 查询 X11 的 EGL visual。Chromium 硬件合成模式会选择与 XWayland 合成窗口匹配的 ARGB8888 visual。窗口桥接失败时返回 EGL 错误，让 Chromium 可以回退。通过 `chrome://gpu`、TAWC 标签的动态图像以及 WebGL 1 和 2 像素读回验证。[Chromium X11 visual 选择](https://chromium.googlesource.com/chromium/src/+/main/ui/gfx/x/visual_manager.cc)
- `libhybris-chromium-wayland-compositing.patch` 为 Chromium 进程内 GPU 打开原生 Wayland EGL 显示。ANGLE 将非空显示句柄标记为 GBM 时，应用显式启用的桥接会忽略该句柄，使用独立连接初始化 EGL，再通过原生窗口所属的 Wayland 连接提交缓冲区。通过 `chrome://gpu`、`android_wlegl` 缓冲区提交和 WebGL 1、2 像素读回验证。[Chromium Wayland EGL 输出](https://chromium.googlesource.com/chromium/src/+/main/ui/ozone/platform/wayland/gpu/wayland_surface_factory.cc)
