# Mobile Terminal 验证记录

## 0.2.8 Chromium WebGL

2026-11-25 通过 USB ADB 在荣耀 MagicPad3 Pro 13.3（YLP-W00、ARM64、Android 17、Adreno 840）验证 Debian Chromium `156.0.8078.108-1~deb13u1`。Mobile Terminal 0.2.8 的 Android 版本号为 10，最低 API 为 29。

动态载入日志确认 ANGLE 使用了 `/usr/lib/hybris/libEGL.so.1`。补齐扩展查询后，跟踪到 `eglGetPlatformDisplayEXT` 请求 `EGL_PLATFORM_GBM_KHR`（`0x31d7`）并传入空设备句柄。Chromium 的兼容入口把该请求转接到 Android EGL 离屏显示，`eglInitialize` 成功。Chromium 从启动时使用软件页面合成后，避免了启动中切换合成方式导致的 WebGL 上下文丢失。

| 场景 | ARM64 真机结果 |
|---|---|
| WebGL 2 | 本地页面执行着色器，右半边在蓝、绿之间切换，`readPixels` 依次返回 RGBA `[0,0,255,255]` 和 `[0,255,0,255]`。页面报告 `ANGLE (Qualcomm, Adreno (TM) 840, OpenGL ES 3.2)`。Wayland 和 XWayland 的独立浏览器配置均获得相同结果。 |
| `chrome://gpu` | Chromium 报告 Adreno 840、`webgl=enabled_readback`（硬件加速并读回）及 `gpu_compositing=disabled_software`（软件页面合成）。 |
| WebGL 1 | Wayland 和 XWayland 均无法创建上下文，GPU 日志报告 `GL_INVALID_FRAMEBUFFER_OPERATION`。自动检测选择 Wayland 作为可选的 WebGL 2 硬件路径。兼容启动默认使用软件渲染，检测通过后可手动选择「WebGL 2 硬件启动」。 |
| 原生 EGL/GLES | 图形菜单重新检测通过。Wayland 和 X11 EGL 探针均报告 Adreno 840，通过各自的像素读回和合成器缓冲区导入检查。 |
| 软件回退 | 关闭硬件窗口后，分别从「兼容启动」和「软件启动」打开 Chromium，两次均成功显示本地测试页，页面报告 WebGL 上下文不可用。 |
| 离线冷启动 | 启用飞行模式并关闭 Wi-Fi 后，冷启动 Mobile Terminal，从「WebGL 2 硬件启动」进入 Chromium。再次打开 `chrome://gpu` 和 APK 随附的本地测试页，页面仍报告 Adreno 840，图形标签显示着色器画面。测试结束后恢复网络设置。 |
| 覆盖安装 | Debian 主目录测试文件在覆盖安装前后的 SHA-256 均为 `5e4a6713ee2ab7471c64b3460031b2c2d9faf599006d2bd89bec0eacea594124`。APK v2 签名通过，安装包和本地 APK 的 SHA-256 均为 `81a67e5b2e9995c89fa374d7ccbe536469809e8b02b741eeeafd6af065f88555`。 |

主机通过两项 Rust 单元测试、Bash PTY 回放及五项 Android JVM 测试。USB ARM64 真机通过六项 instrumentation 测试，包括只通过 WebGL 2 时的默认回退、显式硬件启动和 Chromium 更新后重检。[设备测试日志](../build/chromium-device-test.txt)

[ARM64 APK](../build/mobile-terminal-arm64-debug.apk) · [SHA-256](../build/mobile-terminal-arm64-debug.apk.sha256) · [chrome://gpu 截图](../build/chromium-gpu.png) · [蓝绿动画第 1 帧](../build/chromium-webgl2-1.png) · [第 2 帧](../build/chromium-webgl2-2.png) · [离线截图](../build/chromium-offline.png)

## 0.2.7 Adreno 图形加速

2026-11-24 使用 USB ADB 在荣耀 MagicPad3 Pro 13.3（YLP-W00、ARM64、Android 17、Adreno 840）验证。安装包为 Mobile Terminal 0.2.7（versionCode 9、minSdk 29），通过 APK v2 签名校验。APK 大小 45,171,789 字节，SHA-256 为 `55182ded4b0dcfea330906992719146b452b5c35956c60add71bac580cca5767`。

| 项目 | ARM64 真机结果 |
|---|---|
| Wayland EGL/GLES | 自动检测通过。Debian 客户端报告 `GL_VENDOR=Qualcomm` 和 `GL_RENDERER=Adreno (TM) 840`，像素读回正确，TAWC 的硬件缓冲区纹理导入计数增长。从新 Bash 打开的图形标签连续绘制 560 帧，两张截图中的红条位置不同。 |
| X11 EGL/GLES | 独立检测通过。通过 XWayland 提交 Android 硬件缓冲区，客户端报告同一渲染器。从 Bash 通过 X11 包装器启动后，在图形标签中连续绘制 560 帧并观察到红条移动。 |
| Chromium WebGL | Debian Chromium `156.0.8078.108-1~deb13u1` 的 Wayland GPU 进程报告 `EGL_NOT_INITIALIZED`。本地测试页未能建立 WebGL 上下文，`chrome://gpu` 显示 WebGL 关闭，页面合成使用软件。兼容启动自动使用软件路径。手动选择「软件启动」也能在图形标签打开 `about:blank`。[TAWC 上游的 Chromium/Electron EGL 相关问题](https://github.com/wmww/tawc/issues/1) |
| 软件回退 | 手动选择「软件」后，新 Bash 的 `LIBGL_ALWAYS_SOFTWARE=1`。xeyes 和 Mousepad 均能显示。另用设备测试模拟 X11 检测失败和 Chromium 升级，验证各路径独立回退。 |
| 图形标签恢复 | Mousepad 的未保存测试文本在 2136×3200 竖屏切换到 3200×2136 横屏后仍在。切到 Bash 再返回、打开 Android 设置再返回，编辑区文本和光标都保留。 |
| 覆盖安装与离线 | 覆盖安装前后，主目录测试文件的 SHA-256 均为 `5e4a6713ee2ab7471c64b3460031b2c2d9faf599006d2bd89bec0eacea594124`。启用飞行模式并关闭 Wi-Fi 后冷启动，Wayland 和 X11 各完成 48 帧并报告 Adreno 840。 |

Linux 主机按 `scripts/build-debug` 构建。两项 Rust 单元测试、Bash PTY 字节流回放和五项 Android JVM 测试通过。六项 USB 真机 instrumentation 测试通过，原始结果在本次交付的 `build/adreno-device-test.txt`。

## 0.2.6 APT 安装进度

0.2.5 将 APT 保留在屏幕底行的安装进度写入了命令块，导致进度和软件包日志挤在同一行。旧后端使用分片回放稳定复现。修复后的两项 Rust 单元测试及 Bash PTY 回归通过。分片回放覆盖光标保存、底行定位、两次重绘和清除，并检查解包、配置日志各自完整。

WSL2 使用隔离的 dpkg 根目录运行实际的 APT 安装。记录到四次独立状态更新，完成后清除。测试包安装成功，解包和配置日志留在命令块中，安装进度留在临时状态行。[APT 3.0.3 状态行源码](https://github.com/Debian/apt/blob/3.0.3/apt-pkg/install-progress.cc#L259-L315)

2026 年 11 月 24 日在荣耀 MagicPad3 Pro 13.3（YLP-W00，Android 17，ARM64）通过 USB ADB 覆盖安装。APK v2 签名和 SHA-256 校验通过，设备安装包哈希为 `ca8262c5e25a28091a392b0d5bbdb4f21b9c7c22387f98435271c002b4b36894`，与本地一致。五项 Android JVM 测试和五项设备自动测试通过。[0.2.6 SHA-256 文件](../build/mobile-terminal-arm64-debug.apk.sha256) · [设备测试日志](../build/apt-progress-device-test.txt)

在真机命令块中以隔离的 dpkg 根目录安装临时测试包。安装期间只显示一条独立进度状态，捕获了 40% 的进度条。完成后状态行收起，解包和配置日志各自完整，命令退出码为 `0`，Bash 恢复可输入。

## 0.2.5 终端应用标签

2026 年 11 月 24 日通过 USB ADB 在荣耀 MagicPad3 Pro 13.3（YLP-W00，Android 17，ARM64）覆盖安装。APK v2 签名及 SHA-256 校验通过，设备安装包哈希与本地相同，为 `9439628e786c35fb60f04c85de578adddee4fd7295d6e3310e0ced1767c8dd73`。五项 Android JVM 测试和五项设备测试通过。新增设备用例检查终端画布只修改标签的绘制范围。[0.2.5 SHA-256 文件](https://github.com/northws/Mobile-Terminal/releases/download/v0.2.5/mobile-terminal-arm64-debug.apk.sha256) · [设备测试日志](../build/tui-device-test.txt)

- OpenCode 2.4.1 全屏界面的「＋ 新标签」、标签栏、「键盘」和「关闭标签」可见。用「键盘」输入测试文字，切换至 Bash 后可继续切回 OpenCode，退出状态为 `0`。
- 从菜单直接打开 Vim 并退出。再从 Bash 用 Vim 打开临时文件，确认独立应用标签自动出现。切换至 Bash、暂时关闭并重新打开 Vim 标签后，仍能保存之前输入的文字，并从原 Bash 读回相同文本。
- 通过「其他终端应用」运行 `cat`，在标签内输入并用 `Ctrl+C` 退出。图形应用 `xeyes` 仍可在独立标签打开和关闭。
- 在 Bash 回看 220 行测试输出，切回 Vim 并退出后，原 Bash 仍停在回看位置，可用「回到最新」恢复跟随。
- Bash 显示 `[Y/n]` 时切换标签，返回后发送 `n`，原命令收到回复并以 `0` 退出。单行进程输入和多行 Bash 命令分别恢复滚动状态。

## 0.2.4 OpenCode

OpenCode 2.4.1 的启动探测包含 Kitty 图形查询。0.2.3 的终端后端处理该序列时读取了尚未初始化的 Warp 功能开关，进程以 `101` 退出。修复版在解析终端输出前完成初始化，功能开关使用上游默认值。

WSL2 的两项 Rust 单元测试、真实 Bash PTY 回放和五项 Android JVM 测试通过。回放在两批输出之间切分 Kitty 查询，并覆盖超链接序列和备用屏幕切换。Warp 在 Debian 加载 Bash 配置后追加 `~/.local/bin` 和 `~/.opencode/bin`，两个入口使用相同的搜索路径。最终 ARM64 APK 通过 v2 签名和 SHA-256 校验，哈希为 `4623dcbd25caa4d2df29bbe5d72b17187351a0d5bf12300ebce6f6800892373f`。[0.2.4 SHA-256 文件](https://github.com/northws/Mobile-Terminal/releases/download/v0.2.4/mobile-terminal-arm64-debug.apk.sha256)

2026 年 11 月 24 日通过 USB ADB 在荣耀 MagicPad3 Pro 13.3（YLP-W00，Android 17，ARM64）覆盖安装，设备安装包与本地哈希一致。四项 Android 设备测试通过，其中 Bash 会话测试核对了两个用户安装目录。[设备测试日志](../build/opencode-device-test.txt)

- 「＋ 新标签 → OpenCode」和普通 Bash 中的 `opencode` 均打开已安装的 OpenCode 2.4.1 全屏界面。
- OpenCode 输入框能接收测试文字。清空文字后用 `Ctrl+C` 退出，两个入口均在原标签显示退出码 `0`，随后 Bash 可执行新命令。本轮验收覆盖终端交互，模型请求和供应商登录待单独验证。

## 0.2.3 进度与滚动

通过 USB ADB 在荣耀 MagicPad3 Pro 13.3（YLP-W00，Android 17，ARM64）覆盖安装。设备安装包与本地 SHA-256 一致，为 `6e32bf8e297eda0ad7c36b8f68a77dfe2868c62482079631926ef2501e788816`。APK v2 签名、两项 Rust 单元测试、真实 Bash PTY 回放、五项 Android JVM 测试和四项设备测试均通过。[0.2.3 SHA-256 文件](https://github.com/northws/Mobile-Terminal/releases/download/v0.2.3/mobile-terminal-arm64-debug.apk.sha256) · [设备测试日志](../build/progress-device-test.txt)

- 在 0.2.2 中，同一次 APT 请求重复显示多个读取进度。0.2.3 中读取列表、构建依赖树和读取状态各保留一行。回复 `n` 仍可取消并返回 Bash。
- 同一 Bash 命令分三批各输出 100 行。界面先跟随第一批末尾。手动回看时，收到第二批后仍停在回看位置。点「回到最新」到达第二批末尾，第三批输出后继续自动跟随。
- 后端回放覆盖分片输出中的回车和清行顺序。Android 用例验证保留已完成的行、当前行覆盖、输出样式和短文本覆盖时留下的行尾。

## 0.2.2 交互输入与 Chromium

ARM64 APK 已通过 APK v2 签名和 SHA-256 校验，设备安装包哈希与本地一致，为 `04a52627aa91a4d96df7004577b46e0936a00eec677b84e17fb367410e1b8f47`。WSL2 的两项 Rust 单元测试、真实 Bash PTY 回归及三项 Android JVM 测试通过。

荣耀 MagicPad3 Pro 13.3（YLP-W00，Android 17，ARM64）在 0.2.1 复现了两个问题。APT 类确认能显示在命令块，输入框却不可编辑。Chromium 拒绝以 tawcroot 的虚拟 root 身份启动，普通用户切换和创建用户命名空间也返回权限错误。修复版把「进程输入」送给当前 PTY。Chromium 可经图形应用列表明确选择兼容模式，该模式关闭 Chromium 自身的沙箱，使用独立配置和 Wayland 软件渲染。

- 通过 USB ADB 在 0.2.2 实测 APT 的 `[Y/n]` 提示。输入 `n` 后 APT 显示 `Abort.`，Bash 恢复可输入状态。向测试提示发送空白回车时，程序得到了默认值。
- 从图形应用列表选择 Chromium 时，取消兼容启动会返回 Bash。再次选择并确认后，Chromium 在图形标签中以 Wayland 渲染 `about:blank`，地址栏、工具栏和页面均可见。
- 4 项设备测试通过，其中新增用例在同一个 Bash 命令块先回复 `n`，再发送空行并取得默认值。测试期间发现并修复关闭会话时错误流读管道的崩溃。[设备测试日志](../build/interaction-device-test.txt)

## 0.2.1 名称更新

已检查 ARM64 APK 的显示名为 Mobile Terminal，Android 应用 ID 和签名证书与 0.2.0 一致，可覆盖安装并保留已有 Debian 数据。APK v2 签名、SHA-256 和 3 项 Android JVM 测试通过。[0.2.1 SHA-256 文件](https://github.com/northws/Mobile-Terminal/releases/download/v0.2.1/mobile-terminal-arm64-debug.apk.sha256)

## 0.2.0 图形标签

2026 年 11 月 24 日在荣耀 MagicPad3 Pro 13.3（YLP-W00）、Android 17（API 37）、ARM64 上覆盖安装。当次升级前后，应用私有 Debian 主目录内测试文件的 SHA-256 一致。

WSL2 构建了 TAWC 的 Wayland 合成器、Android XWayland、`xkbcomp` 和键盘映射。APK v2 签名、归档完整性和 SHA-256 校验通过。合成器、XWayland、`xkbcomp`、tawcroot 和 Warp Linux 后端均为 AArch64。Rust 的 2 项单元测试及真实 Bash PTY 测试通过，Android 的 3 项 JVM 测试通过。该版本的验收 SHA-256 为 `2b7c67b6d2fa21e997c31910d580a4bf4a09c08f762ba23897b7a1f864967dea`。

| 场景 | ARM64 真机结果 |
|---|---|
| 安装示例 | 从「＋ 新标签 → 图形应用」发起，APT 安装了 Mousepad、xeyes 和 Noto CJK 字体。平板直连取件较慢，重试时临时通过 USB 转发连接 Debian 官方仓库。软件包由 APT 校验。安装完成后删除了临时 APT 配置并关闭转发。 |
| Wayland 与中文输入 | 从应用列表打开 Mousepad，以百度输入法荣耀版输入「你好」。保存对话框留在 Mousepad 标签。用同一输入法命名为 `~/Downloads/图形测试.txt`。切到 Bash 后读回「你好」，文件为 6 个 UTF-8 字节，覆盖安装最终 APK 后哈希仍为 `670d9743542cae3ea7ebe36af56bd53648b0a1126162e78d81a32934a711302e`。 |
| 关闭确认 | 修改 Mousepad 文档后用 Warp 的「关闭标签」，编辑器弹出保存确认。取消后同一标签及未保存文本均保留。 |
| X11 与 Bash | `xeyes` 打开独立标签，触摸滑动会改变瞳孔位置。关闭窗口后原命令块退出 0。执行 `xeyes &` 时，切回 Bash 可以立即输入。xeyes 首次绘制时在左上角出现了缩小的残影，触摸交互和关闭仍可用。 |
| 多标签与后台 | Bash、Mousepad、xeyes 同时存在。横屏切到竖屏，打开 Android 设置再返回，Warp 进程 PID 一致。Mousepad 的未保存文本保留，回来后可继续输入。 |
| 飞行模式下冷启动 | 飞行模式开启且 Wi-Fi 关闭。用强制停止遗留的 X11 锁和套接字复测，修复版启动后重新创建两项。Bash 启动的 xeyes 成功渲染，关闭后直接回到 Bash，命令退出 0。Mousepad 离线打开同一份中文文件。再次强制停止后，从图形应用列表直接打开 xeyes 也通过。 |
| 终端和文件回归 | 最终 APK 在飞行模式下通过 3 项设备测试，覆盖 Bash 双会话、退出和中断，以及导入取消与越界链接。[测试日志](../build/graphics-device-test.txt) |

Android 可在 XWayland 清理前终止进程。修复版只在新 Android 进程初始化合成器时移除旧的 `:0` 锁和套接字，前台服务在同一进程中重建时继续使用当前的监听套接字。关闭图形标签后回到最近使用的标签。

最终安装在设备上的 `base.apk` 哈希与工作区 APK 一致。Mousepad 的文件编辑与保存通过。缺少会话 D-Bus 时，Mousepad 报告无法写入偏好设置。

验收样本和测试 APK 已清理。飞行模式、Wi-Fi、旋转和输入法已恢复到测试前的设置。平板已从 WSL 分离，应用停在包含 Mousepad 和 xeyes 的图形应用列表。

## 0.1.0 终端首版

## 主机

- Rust 单元测试通过，覆盖帧分片、错误长度和截断。
- Debian WSL2 上的真实 Bash PTY 测试通过。用例覆盖中文管道输出、退出码、目录和环境变量隔离、`stty size`、`Ctrl+C` 和备用屏幕事件。
- 同一套 Bash 用例在 Linux x86_64 的 tawcroot 中再次通过，使用独立 Debian 根目录及临时用户主目录。
- GNU/Linux ARM64 后端和 NDK 编译的 Android ARM64 tawcroot 均通过构建。
- 3 项 Kotlin 单元测试通过，覆盖帧分片、大小边界及文件名约束。设备测试 APK 已编译。

## APK

调试 APK 版本为 0.1.0。通过了 APK v2 签名验证、归档校验和 SHA-256 校验。包内 tawcroot 和 Warp 后端的 ELF 架构均为 AArch64。该版本的验收 SHA-256 为 `db95e0ae3f82efbfa5bf6d31c1867527eccb33910ea5cdcd61842bf2bfa840f6`。

2026 年 11 月 24 日覆盖安装的最终 APK 与工作区归档 SHA-256 一致。

## ARM64 Android

2026 年 11 月 23 至 24 日在荣耀 MagicPad3 Pro 13.3（YLP-W00）、Android 17（API 37）、`arm64-v8a` 真机上验收。Windows ADB 未枚举设备，经 USB/IP 将平板临时转接到专用 Debian WSL2，由 WSL ADB 通过 USB 安装和测试。

| 场景 | 结果 |
|---|---|
| 全新安装与断网恢复 | 无网时应用显示中文错误与重试入口。下载中断后保留部分文件，覆盖安装仍保留 6,315,904 字节，前缀 SHA-256 与完整镜像相同。终止应用后再次初始化，安装器复用了已校验、解包的目录。 |
| 设备直连下载 | 平板从 GitHub 镜像源续传时连续两次超时。通过 USB 补齐同一份固定镜像，应用校验完整 SHA-256 并解包。软件包由 Debian 官方仓库经临时 USB 转发下载并验证，Bash 自检及首次进入终端通过。设备公网直连完成全量初始化仍待复测。 |
| 离线冷启动 | 开启飞行模式并关闭 Wi-Fi，强制停止后冷启动，两个独立 Bash 标签都执行了命令并显示退出码 0。 |
| 中文输入法 | 使用平板自带的百度输入法荣耀版，点按拼音键并选择一次「你好」。命令块中的 `echo 你好` 输出恰好一行「你好」。 |
| 全屏终端与旋转 | `vim` 进入全屏网格。键盘布局稳定后输入一行脚本，保存退出，再从命令块执行并得到 `WARP_VIM_OK`。横屏切到竖屏后会话和命令块仍在。已验证软键盘弹出和收起。 |
| 后台与持久化 | Bash 执行 12 秒任务期间切到系统设置。返回后应用 PID、工作目录和 Bash 环境变量保持，命令输出已更新。APK 覆盖前后，后台任务标记文件、脚本和二进制文件的 SHA-256 一致。 |
| 文件选择器往返 | 中文名脚本和 32,768 字节的二进制文件均经 Android 系统文件选择器导入、导出，原件、主目录副本和导出件的 SHA-256 一致。同名导入生成 `(1)` 副本。导出完成后立即重新打开文件菜单可继续操作，取消选择会返回应用。 |
| 飞行模式下的设备回归 | 3 项通过。覆盖真实 Bash 双会话、管道、退出码、`Ctrl+C`、复制中途取消后原件保留与部分副本清理，以及越界符号链接隐藏和拒绝导出。[ADB 测试日志](../build/device-test.txt) |

## 真机发现与修复

- Android 拒绝应用私有目录内的硬链接。Debian 解包使用延迟设置目录权限的归档读取器，必要时以相对符号链接指向已解包的文件。导入改用独占创建，避免覆盖已有文件。
- 下载超时和断网错误显示为中文，安装重试保留已验证的解包目录。
- 文件传输结束即恢复菜单入口，操作提示可继续显示。深色界面使用浅色系统栏图标。

验收样本、设备测试包和临时 ADB 转发已清理。Wi-Fi、飞行模式和自动旋转已恢复。平板已从 WSL 分离。撤销 Windows USB/IP 共享的管理员确认被取消，共享状态仍为 `Shared`。
