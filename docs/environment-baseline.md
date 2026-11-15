# 阶段 0：工具链、设备与测试数据基线

日期：2026-11-15

状态：阶段 0 已完成；组件安装、原生编译、测试数据与 Flutter APK 真机验证均通过。

## 1. 工具版本

| 工具 | 已核实版本 | 核实方式 |
| --- | --- | --- |
| Flutter | 3.47.9 stable，revision `c197ca5199` | `flutter --version`、doctor |
| Dart | 3.13.7 | Flutter 版本输出 |
| JDK | Oracle 21.0.9+7-LTS-338 | doctor、Gradle Launcher/Daemon JVM |
| Gradle | 9.3.1 | 隔离工程 Wrapper 的 `--version` |
| AGP | 9.1.0 | Flutter 生成工程的插件版本 |
| Kotlin Android 插件 | 2.4.0 | Flutter 生成工程的插件版本 |
| Gradle 内嵌 Kotlin | 2.2.21 | Gradle `--version`；与应用插件版本分开记录 |
| Android SDK Platform | android-36 revision 2，另保留 android-37.0 | Android CLI 包列表 |
| Build Tools | 36.0.0 | Android CLI 包列表 |
| Platform Tools / ADB | 37.0.1 | Android CLI 包列表 |
| NDK | 28.2.13676358 / r28c | 包列表、`source.properties` |
| Clang | 19.0.1 | CMake 编译器识别 |
| CMake | 3.22.1-g37088a8-dirty | SDK 内 CMake `--version` |
| Ninja | 1.10.2 | SDK 内 Ninja `--version` |
| Android CLI | 1.0.16625370 | `android --version` |
| Emulator | 37.2.12 | doctor、包列表；当前没有配置 AVD |

AGP 和 Kotlin 插件版本来自生成工程，已通过实际依赖解析和打包验证。NDK、CMake 及 API 36 已完成安装，不只是查询到可下载版本。

## 2. 本机路径与环境使用

| 用途 | 本机路径 |
| --- | --- |
| Flutter | `E:\Projects\.toolchains\flutter` |
| SDK | `I:\Software\Android\SDK` |
| JDK | `D:\Software Environment\jdk-21.0.9` |
| NDK | `I:\Software\Android\SDK\ndk\28.2.13676358` |
| CMake | `I:\Software\Android\SDK\cmake\3.22.1` |

在仓库根目录的 PowerShell 中加载本机入口：

```powershell
. ./.local/android-env.ps1
flutter doctor -v
adb devices -l
```

`.local/android-env.ps1` 保存本机绝对路径，已被 Git 忽略。它调用可提交的 [环境脚本](../tools/enter-android-env.ps1)，只修改当前 PowerShell 进程的环境变量和 PATH。其他机器通过脚本参数提供各自路径，不修改共享 Flutter SDK 源码或系统 PATH。

Android toolchain 检查通过，doctor 确认所有 Android 许可证已接受。加载环境后 Flutter/Dart 的 PATH 警告已消除。doctor 仍报告 Chrome 不存在；这是 Web 开发检查，不影响本项目的 Android 验收。

## 3. 已连接设备基线

| 属性 | 实测值 |
| --- | --- |
| 设备代号 | Xiaomi `sheng` |
| 型号 | 24018RPACC |
| SoC | SM8550 |
| Android | 16 / API 36 |
| 安全补丁 | 2026-06-01 |
| 主 ABI | arm64-v8a，系统也报告 armeabi-v7a/armeabi |
| 内核 | 5.15.178-android13-8-00021-g6f2f96be86b9-ab13729987 |
| 内存 | `/proc/meminfo` 的 MemTotal 为 11,728,828 KiB |
| 内存页 | 4096 字节 |
| SELinux | Enforcing，验证期间未关闭 |
| 数据加密 | encrypted |
| root 方案 | KernelSU，`su -v` 为 `3.3.0:KernelSU` |
| root 身份 | 实测 `uid=0`、`context=u:r:ksu:s0` |
| ADB shell 身份 | UID 2000，`context=u:r:shell:s0` |
| `/data` | F2FS，块大小 4096 |
| `/storage/emulated/0` | FUSE，块大小 4096 |
| `/data/media/0` | root 可见的底层 F2FS 视图 |

该 arm64 真机已通过临时应用的普通 UID 安装启动验证，也通过了 ADB 发起的已授权 `su` 验证。正式应用的 libsu 授权、普通应用 UID 的扫描范围和所有文件访问权限行为，需要阶段 3/4 单独验证。ADB shell 的权限不能代替应用沙箱权限。

## 4. 受控测试数据

创建脚本：[create-fixtures.sh](../tools/phase0/create-fixtures.sh)。复现说明：[阶段 0 工具说明](../tools/phase0/README.md)。

本次有效运行 ID 为 `run-20261115-02`：

- 原生样例：`/data/local/tmp/disksize-phase0/run-20261115-02`。
- 共享样例：`/sdcard/Download/DiskSize-phase0/run-20261115-02`。
- 共享样例对应的底层视图：`/data/media/0/Download/DiskSize-phase0/run-20261115-02`。

首次 shell 创建硬链接被设备 SELinux 拒绝，随后改为 root 创建新运行目录；首次运行的两个不完整目录已仅按确切路径清理。有效数据没有通过关闭 SELinux 创建。

已使用设备 `find` 与 `stat` 枚举和去重，核验值如下：

| 指标 | 原生样例 |
| --- | --- |
| 普通文件目录项 | 136 |
| 按设备号和 inode 去重后的普通文件 | 135 |
| 目录数量，包含根目录 | 31 |
| 符号链接数量 | 2 |
| 普通文件逐目录项逻辑总量 | 1,073,750,173 字节 |
| 去重后普通文件逻辑总量 | 1,073,746,077 字节 |
| `du -sk` 观测值 | 645 KiB |
| `du -sb` 观测值 | 1,073,857,846 字节 |

共享样例包含两个普通文件、两个目录（含根目录）；普通文件数量和 4,105 字节逻辑总量已通过 `find`/`stat` 另行核验。底层与 FUSE 视图的 `du -sk` 均观测到 15 KiB。

`du -b` 包含目录及链接自身大小，与本项目“普通文件逻辑总量”的定义不同，不能直接作为后者的断言。实际占用包含文件系统相关行为，645/15 KiB 仅是本次设备观测值。

## 5. 原生工具链与行为验证

使用 CMake 3.22.1、NDK r28c、C++20、Android API 29，分别构建 arm64-v8a 与 x86_64 环境探针；两种 ABI 均通过 `-Wall -Wextra -Werror` 构建。

arm64 探针已推送到真机执行，验证：

- 普通 shell 运行报告 UID 2000、64 位指针、4096 字节页大小。
- root 运行报告 UID 0。
- 4 KiB 文件及其硬链接具有相同设备号和 inode，`nlink=2`。
- 1 GiB 稀疏文件报告 `st_size=1,073,741,824`，`st_blocks=0`。
- shell 读取受保护目录中的文件失败并报告 `errno=13`；root 读取成功，大小为 8 字节。
- 共享存储同一文件在 FUSE 与底层 F2FS 中报告相同 inode、不同设备号，证明需要主动选择单一扫描视图，不能跨视图仅靠设备号/inode 去重。

`llvm-readelf` 检查到两种 ABI 探针的全部 `LOAD` 段均使用 `0x4000` 对齐。该结论仅覆盖环境探针的 ELF 布局；尚未完成 16 KB 页设备运行验证，也不替代正式 APK 中所有原生库的打包及运行验收。

## 6. Flutter Android 构建验收

临时工程位于 `.local/phase0/flutter-probe`，applicationId 为 `dev.disksize.phase0_probe`。它只用于环境验证，未在仓库根目录创建正式 Flutter 应用。

已执行 Flutter debug APK 构建，首次依赖下载遇到 Gradle Plugin Portal 的 TLS 握手中断。使用同一 JDK 单独访问该依赖、Maven Central 和 Google Maven 均返回 HTTP 200；Flutter 的自动重试已越过原失败点。

自动重试后成功完成 `assembleDebug`，未调整 TLS 校验、仓库地址或共享 Flutter SDK。构建期间另有 SDK XML version 4 的兼容警告，未阻止打包；当前保留该观测，不通过修改 SDK 元数据掩盖警告。

| 验证项 | 结果 |
| --- | --- |
| Flutter Android debug APK | 成功，arm64 目标 |
| APK 大小 | 77,272,915 字节 |
| APK SHA-256 | `38326932D7FBA117407635C304101E08D9F7915D86EF870DA55EE450E005C76C` |
| APK 签名 | `apksigner verify --verbose` 通过，v2 签名 |
| 真机安装 | `adb install --no-incremental` 成功 |
| Activity 启动 | `am start -W` 报告 `Status: ok` |
| 应用进程 | `u0_a357`，进程名 `dev.disksize.phase0_probe` |
| debug `run-as` 身份 | UID/GID 10357；不是 root 或 shell UID |
| 清理 | 已停止并卸载本次创建的临时验证应用；APK 与日志保留在 `.local/phase0/` |

至此，阶段 0 的组件、构建链、arm64 普通应用目标、root 目标和测试数据基线均已验收。正式仓库的 Flutter/Android/C++ 应用骨架仍由阶段 1 创建。

## 7. 后续覆盖范围

- API 29 的 legacy 存储分支及 API 30+ 权限撤销：阶段 3/9。
- 正式应用自身 UID、libsu RootService 和 root 授权拒绝：阶段 3/4。
- 独立非 root 系统、x86_64 运行及 16 KB 页设备：集成测试矩阵补充；当前无 AVD。
- 1 万/10 万/100 万条目性能基线：扫描核心和索引实现后在阶段 5 测量，当前不声明性能指标。

原始日志、设备探针结果和编译产物位于 `.local/phase0/`，均不提交到 Git。文档只记录验收所需的设备属性，不记录设备序列号或用户文件内容。
