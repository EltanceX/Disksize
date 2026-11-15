# 阶段 1：Flutter / Android / C++ 骨架

日期：2026-11-15

状态：阶段 1 已完成，Android 双 ABI 构建和真机跨进程验收通过。

## 1. 当前范围

本阶段建立正式应用骨架和真实原生自检链路，不启动目录遍历，也不申请 root 或存储权限。首页显示原生核心版本、ABI、UID/PID、页大小、自检状态和连接失败后的重试入口。

扫描配置、会话状态、取消标志与错误类型已定义。实际扫描任务的创建、进度、取消接口及后台生命周期在阶段 2/3 接入；root 后端在阶段 4 接入。

## 2. 调用与进程边界

```text
Flutter 首页
  → DiagnosticsClient
  → Pigeon ScannerHostApi（异步）
  → Kotlin ScannerPlatformApi
  → Binder / AIDL IScanCoordinator
  → CoordinatorService（普通应用 UID，:scanner 进程）
  → NativeBridge / JNI
  → C++ disksize_core
```

`CoordinatorService` 使用显式绑定，`exported=false`、`isolatedProcess=false`。它与 UI 使用相同应用 UID，PID 不同，不使用 root。AIDL 接口另校验调用 UID。

Pigeon 30 使用 Kotlin 协程生成异步处理函数，因此固定依赖 `kotlinx-coroutines-android:1.11.0`。服务绑定在 Android 主线程管理；实际 Binder 自检调用在 `Dispatchers.IO` 执行，整体设置 10 秒超时。连接断开后返回可重试错误，下一次请求可重新绑定；Flutter 引擎清理时解除绑定。

服务侧错误通过 AIDL Parcelable 的错误码和错误信息传回，再映射为 Pigeon 的 `FlutterError`，使用公开 SDK API。错误码包括 `native-failure`、`service-unavailable`、`service-timeout` 和 `diagnostics-failure`。

返回数据包含原生协议版本 1、核心版本 `0.1.0`、原生 ABI、主进程与服务 PID/UID、有效 UID、页大小、指针位宽、自检标志及应用自身目录。网关验证服务身份及协议，避免把错误进程的结果显示成正常扫描服务。

阶段 1 的服务仅提供短时自检，尚不声明前台服务或持久扫描能力。

## 3. 主要文件

| 文件或目录 | 职责 |
| --- | --- |
| `lib/app/disksize_app.dart` | 应用入口、主题与首页注入 |
| `lib/features/scan/presentation/scan_home_page.dart` | 自检状态、信息展示、重试与页面生命周期处理 |
| `lib/features/scan/domain/scan_models.dart` | 扫描配置、会话状态、取消标志与错误模型 |
| `lib/platform/diagnostics_client.dart` | Flutter 侧平台接口适配与测试替换点 |
| `pigeons/scanner_api.dart` | Dart/Kotlin 共享的类型与协议源文件 |
| `lib/platform/generated/scanner_api.g.dart` | Pigeon 生成的 Dart 通道代码 |
| `android/app/src/main/kotlin/com/example/disksize/bridge/` | Pigeon Kotlin 生成代码和网关实现 |
| `android/app/src/main/kotlin/com/example/disksize/scan/` | 服务绑定、服务实现和 JNI 加载 |
| `android/app/src/main/aidl/` | 服务 IPC 接口与类型化诊断 Parcelable |
| `android/app/src/main/cpp/core/` | 不依赖 Flutter/JNI 的 C++ 核心类型、自检与取消令牌 |
| `android/app/src/main/cpp/jni/` | C++ 数据到 JNI 的转换 |
| `test/diagnostics_page_test.dart` | 页面成功、失败重试与销毁后的异步回调测试 |
| `integration_test/diagnostics_test.dart` | 真机 Pigeon → Binder → JNI 的端到端验证 |

CMake 将纯 C++ 核心构建为静态库，再链接到 `libdisksize_jni.so`，方便后续普通和 root 工作进程复用同一核心。原生代码启用 C++20、`-Wall -Wextra -Werror`；页大小通过 `sysconf` 获取。

## 4. 固定构建配置

| 项目 | 值 |
| --- | --- |
| Flutter / Dart | 3.47.9 / 3.13.7 |
| applicationId / namespace | `com.example.disksize`，开发占位 |
| minSdk / compileSdk / targetSdk | 29 / 36 / 36 |
| Gradle / AGP / Kotlin | 9.3.1 / 9.1.0 / 2.4.0 |
| NDK / CMake | 28.2.13676358 / 3.22.1 |
| ABI | arm64-v8a、x86_64 |
| Pigeon / Coroutines Android | 30.0.2 / 1.11.0 |

Gradle 构建逻辑只记录版本，不记录本机 SDK/JDK 绝对路径。保留 Flutter 模板的 Kotlin/Android 兼容配置。当前 release 配置沿用开发签名，仅用于开发验证，不能视作发布签名配置。

项目通过 `disable-abi-filtering=true` 关闭 Flutter 插件自动注入的默认 ABI 列表，再由项目的 NDK 与 CMake 配置明确选择两个 64 位 ABI。该设置用于防止自动加入首版不支持的 armeabi-v7a，未修改共享 Flutter SDK。

包名替换时，应同步修改目录、Kotlin/AIDL package、Manifest、Pigeon 配置、JNI 导出符号、R8 保留规则、服务身份校验及测试断言。

## 5. 生成与验证命令

在仓库根目录加载本机环境，再生成桥接代码：

```powershell
. ./.local/android-env.ps1
flutter pub get
./tools/generate-bridge.ps1
```

`generate-bridge.ps1` 调用锁定版本的 Pigeon，并格式化 Dart 生成文件。Dart 与 Kotlin 产物均提交，禁止手工修改生成代码后不更新协议源文件。

```powershell
flutter analyze
flutter test
flutter build apk --debug --target-platform 'android-arm64,android-x64'
flutter test integration_test/diagnostics_test.dart -d <device-id>
```

## 6. 验收记录

- Dart 静态分析：通过。
- 三项页面测试：通过，覆盖正常结果、失败重试与页面销毁后返回结果。
- Pigeon Dart/Kotlin 重新生成：文件哈希一致，通过。
- Android 双 ABI debug APK 构建：通过，APK 中仅含 arm64-v8a、x86_64，两者均包含 Flutter 引擎与 JNI 库。
- API 打包配置：`aapt` 确认 minSdk 29、targetSdk 36、compileSdk 36。
- 真机跨进程集成测试：通过，验证原生版本、协议、ABI、UID/PID、页大小、位宽、自检及连接复用。
- 普通运行入口安装、启动及首页截图检查：通过，最终保留普通首页版本，未保留测试入口作为交付应用。
- 非导出服务检查：ADB shell 显式启动 `CoordinatorService` 被 Android 拒绝。
- `zipalign -c -P 16`：通过；两个 ABI 的自编译 JNI 库 `LOAD` 段满足至少 16 KB 对齐。

本次最终普通应用运行观测：

| 指标 | 值 |
| --- | --- |
| 主进程 / 服务进程 | `com.example.disksize` / `com.example.disksize:scanner` |
| 主进程 PID / 服务 PID | 14277 / 16968 |
| 应用 UID / 服务 UID | 10359 / 10359 |
| Native ABI / 位宽 | arm64-v8a / 64 |
| Native 页大小 | 4096 字节 |
| 核心版本 / 自检 | 0.1.0 / true |

PID 和 UID 是本次安装的观测值，测试通过相对关系验证身份，不将这些数值硬编码为固定断言。

最终 APK：`build/app/outputs/flutter-apk/app-debug.apk`，165,953,967 字节，SHA-256 为 `77EE5ECEBA5325439757ED0CF11B80A5014456D62E1EA6839F09DEDB7511EDAE`。这是包含两个 ABI 的 debug 骨架，已安装在测试设备上；不表示已完成发布签名、体积优化或商店发布。

对齐检查覆盖本次 APK 的 ZIP 布局与自编译 JNI ELF，不替代未来 16 KB 页设备运行验证；x86_64 本次只验证构建与打包，尚无模拟器运行结果。

日志与设备截图保存在忽略提交的 `.local/phase1/`。本阶段完成后，下一步是阶段 2 的只读扫描核心与受控文件树验证。
