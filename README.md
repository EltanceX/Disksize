# DiskSize

面向开发者的 Android 存储分析工具，采用 Flutter、Kotlin 和 C++，支持普通权限与可选 root 模式。

阶段 0～5 已完成。应用支持普通模式下扫描自身目录、应用外部目录、主共享存储和自定义真实路径，提供权限引导、进度/取消、有限结果及错误范围预览，并在进程死亡、授权撤销和系统超时后记录中断状态。Root 模式通过显式授权、libsu 6.0.0 RootService 和二进制管道调用同一扫描库，支持挂载入口、原始名称、取消和断开恢复。Flutter 经 Pigeon、独立 `:scanner` 服务和 JNI 调用共用 C++ 核心；普通 UID 的 SQLite 索引负责硬链接归属、最终聚合及分页查询。运行环境检查保留在首页右上角。

当前为只读原型，Android 入口最多 2,000,000 节点、512 层深度。首页保留 64 条有界预览；索引 API 支持子项、大文件、详情和错误列表，每页最多 100 条，完整浏览界面将在阶段 6 实现。使用 root 时点击“请求 root 访问”并按管理器授权；若显示拒绝，在管理器中允许 DiskSize 后重试，再切换后端。应用不会在普通启动时自动请求 root。

## 开发环境

Flutter 3.47.9 / Dart 3.13.7，JDK 21，Android compileSdk/targetSdk 36、minSdk 29，NDK 28.2.13676358，CMake 3.22.1。支持 arm64-v8a 和 x86_64。

在 PowerShell 中配置当前终端环境；本机已有 `.local/android-env.ps1` 时可直接加载：

```powershell
. ./.local/android-env.ps1
# 其他机器通过参数配置自己的工具链路径：
# . ./tools/enter-android-env.ps1 -FlutterSdk '<Flutter SDK>' -AndroidSdk '<Android SDK>' -Jdk '<JDK>'

flutter pub get
./tools/generate-bridge.ps1
flutter run -d <device-id>
```

开发占位包名为 `com.example.disksize`，发布前应统一替换 applicationId、Kotlin/AIDL 包、Pigeon 输出位置、JNI 符号及相关校验和测试。

## 验证与构建

```powershell
flutter analyze
flutter test
flutter test integration_test/diagnostics_test.dart -d <device-id>
flutter test integration_test/scanning_test.dart -d <device-id>
flutter build apk --debug --target-platform 'android-arm64,android-x64'
./tools/lint-android.ps1
```

真机集成测试验证真实跨进程链路，包括普通应用 UID、不同 PID、JNI 自检及服务连接复用。测试需要设备已允许 ADB 安装和调试。

原生扫描验证：`./tools/phase2/verify.ps1 -Serial '<device-id>'`，需要已授权 ADB 与 root 的测试设备，构建双 ABI 后执行真实文件系统测试。CLI 用法、可选阶段 0 样本核验和日志位置见[阶段 2 工具说明](tools/phase2/README.md)。

普通模式生命周期验证见[阶段 3 工具说明](tools/phase3/README.md)。设备测试使用普通应用 UID；主机脚本可验证真实前后台切换、系统超时和授权撤销，并在结束时恢复测试设置及正常入口 APK。深层 FUSE 目录的遍历性能仍需后续优化，记录见阶段 3 文档。

Root 真机验证和协议单元测试见[阶段 4 工具说明](tools/phase4/README.md)。当前授权驱动针对本机 KernelSU 平板布局；其他管理器需替换授权驱动。应用通过 Binder 校验普通客户端 UID，root 进程只读取目标元数据；会话及后续索引由普通应用 UID 管理。

索引及规模测试见[阶段 5 工具说明](tools/phase5/README.md)。真机已验证百万真实节点的增量扫描、SQLite 聚合和 9,990 页完整遍历；扫描与入库约 59 秒、聚合约 54 秒，新增索引约 1 GiB。详细内存、查询延迟、root 验证和限制见[阶段 5 验收](docs/phase5-index.md)。

修改 `pigeons/scanner_api.dart` 后运行 `tools/generate-bridge.ps1`，将 Dart 与 Kotlin 生成文件一同提交。应用的 `pubspec.lock`、Gradle Wrapper 和生成桥接代码均纳入版本管理。

本机路径、原始设备日志及临时构建验证工程放在忽略提交的 `.local/`；构建输出、签名材料和 `android/local.properties` 也已忽略。

## 文档

- [第一版实现方案](docs/v1-design.md)
- [实施步骤与验收标准](docs/v1-implementation-plan.md)
- [阶段 0 环境基线](docs/environment-baseline.md)
- [阶段 1 骨架与验收](docs/phase1-skeleton.md)
- [阶段 2 扫描核心与验收](docs/phase2-scanner.md)
- [阶段 3 普通模式与生命周期](docs/phase3-ordinary-lifecycle.md)
- [阶段 4 Root 后端与验收](docs/phase4-root-backend.md)
- [阶段 5 索引、聚合与分页验收](docs/phase5-index.md)
