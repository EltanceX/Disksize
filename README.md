# DiskSize

面向开发者的 Android 存储分析工具，采用 Flutter、Kotlin 和 C++，支持普通权限与可选 root 模式。

阶段 0～8 已完成。应用支持普通/root 模式的只读扫描、权限引导、进度/取消和中断恢复，并提供 TreeSize 风格的目录与大文件列表、矩形树图、快照及增长对比。Flutter 经 Pigeon、独立 `:scanner` 服务和 JNI 调用共用 C++ 核心；libsu 6.0.0 RootService 负责可选 root 访问，普通 UID 的 SQLite 索引负责硬链接归属、最终聚合和稳定分页。手机使用单列和详情抽屉，宽屏提供详情分栏。运行环境检查保留在首页右上角。

当前为只读原型，Android 入口最多 2,000,000 节点、512 层深度。首页保留 64 条有界预览；完整浏览每页 50 项，API 每页最多 100 条。点击最近会话或“浏览目录与大文件”进入索引；大文件列表限定当前目录范围。卷差额显示采样时间和统计范围，不能当作可清理空间。使用 root 时点击“请求 root 访问”并按管理器授权；若显示拒绝，在管理器中允许 DiskSize 后重试，再切换后端。应用不会在普通启动时自动请求 root。

首页右上角“快照与增长对比”可选择基准和另一份快照，查看新增、删除、容量变化和增长目录。最终聚合结果自动保存，部分扫描保留未知范围；保留标记防止误删，删除只清理应用自己的索引。重命名按删除和新增展示。稳定卷 UUID 支持跨启动对比；系统无法确认卷身份或旧索引缺少规则信息时会拒绝对比，需要重新扫描。

快照库的“一键删除快照”默认选中所有非保留快照，包含更早快照；可取消勾选或自行选择，确认后批量删除。已保留快照受保护，原文件不受影响。

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
./tools/phase6/verify.ps1 -Serial '<device-id>'
./tools/phase8/verify.ps1 -Serial '<device-id>' -RootInstall
flutter build apk --debug --target-platform 'android-arm64,android-x64'
./tools/lint-android.ps1
```

真机集成测试验证真实跨进程链路，包括普通应用 UID、不同 PID、JNI 自检及服务连接复用。测试需要设备已允许 ADB 安装和调试。

原生扫描验证：`./tools/phase2/verify.ps1 -Serial '<device-id>'`，需要已授权 ADB 与 root 的测试设备，构建双 ABI 后执行真实文件系统测试。CLI 用法、可选阶段 0 样本核验和日志位置见[阶段 2 工具说明](tools/phase2/README.md)。

普通模式生命周期验证见[阶段 3 工具说明](tools/phase3/README.md)。设备测试使用普通应用 UID；主机脚本可验证真实前后台切换、系统超时和授权撤销，并在结束时恢复测试设置及正常入口 APK。深层 FUSE 目录的遍历性能仍需后续优化，记录见阶段 3 文档。

Root 真机验证和协议单元测试见[阶段 4 工具说明](tools/phase4/README.md)。当前授权驱动针对本机 KernelSU 平板布局；其他管理器需替换授权驱动。应用通过 Binder 校验普通客户端 UID，root 进程只读取目标元数据；会话及后续索引由普通应用 UID 管理。

索引及规模测试见[阶段 5 工具说明](tools/phase5/README.md)。真机已验证百万真实节点的增量扫描、SQLite 聚合和 9,990 页完整遍历；扫描与入库约 59 秒、聚合约 54 秒，新增索引约 1 GiB。详细内存、查询延迟、root 验证和限制见[阶段 5 验收](docs/phase5-index.md)。

目录、大文件界面和真实索引交互验证见[阶段 6 工具说明](tools/phase6/README.md)。本阶段将 schema 升级至 3、增量协议升级至 v3，保留旧数据库数据；验收与范围说明见[阶段 6 文档](docs/phase6-explorer.md)。

矩形树图支持 0～6 级深度，默认级别 3；当前目录为级别 0，下钻后重新计级。目录标题栏、嵌套色块和层级图例显示父子关系，旧索引可直接使用。范围与实测见[阶段 7 验收](docs/phase7-treemap.md)。快照与增长对比将 schema 升至 4，原生协议继续使用 v3，旧索引保留；兼容性、未知范围与删除边界见[阶段 8 验收](docs/phase8-snapshots.md)，复现步骤见[阶段 8 工具说明](tools/phase8/README.md)。

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
- [阶段 6 目录与大文件界面](docs/phase6-explorer.md)
- [阶段 7 矩形树图](docs/phase7-treemap.md)
- [阶段 8 快照与增长对比](docs/phase8-snapshots.md)
