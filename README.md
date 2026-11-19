# DiskSize

面向开发者的 Android 存储分析工具。使用目录列表、大文件排行和矩形树图查看空间占用，保存扫描快照并比较增长，也可重新扫描快照中的指定文件夹。

采用 Flutter、Kotlin 和 C++，支持普通权限及可选 Root 后端。扫描只读取文件元数据；快照保留、删除和局部更新操作管理应用自己的索引数据。

## 功能

- **目录与大文件浏览**：显示逻辑大小、文件系统报告占用、硬链接归属及未完成范围；支持排序、分页和祖先导航，大文件列表限定在当前目录及其后代。
- **矩形树图**：支持 0～6 级深度，默认 3 级；单击选中、双击目录进入，标题栏和图例呈现层级。
- **快照与增长对比**：扫描最终聚合后自动保存快照，可查看新增、删除和容量变化，按稳定卷身份检查兼容性。
- **文件夹局部重扫**：在快照内选择目录，手动重新扫描并原子更新已有快照；提供进度、取消、持久任务及中断恢复。
- **快照管理**：显示本地索引空间估算，支持保留保护、单个删除及批量删除；删除快照不删除被扫描的文件。
- **普通 / Root 扫描**：提供权限引导、进度、取消及中断恢复。Root 需手动请求授权，索引始终由普通应用 UID 的独立服务持有。
- **适配手机与宽屏**：手机使用单列和详情抽屉，宽屏提供详情分栏；主菜单两侧留白可滚动，目录点击和树图双击下钻时保留滚动位置。
- **外部打开**：将当前文件或目录交给系统文件管理器或兼容查看器；可访问范围取决于接收应用权限，详见[外部打开说明](docs/external-open.md)。

## 使用流程

1. 在首页选择存储卷、应用目录或自定义真实路径，开始扫描。访问受限路径时，可手动请求 Root 并选择 Root 后端。
2. 点击最近会话或“浏览目录与大文件”，使用目录、大文件、树图切换浏览结果。
3. 从首页右上角进入“快照与增长对比”，选择两份兼容快照查看变化；已保留快照受删除保护。
4. 在快照目录、条目详情或树图选中目录后，选择“重新扫描此文件夹”，确认范围后更新当前快照。

局部重扫只在完整成功后替换目标子树；失败或提交前取消保留旧结果。更新后的快照包含不同采集时间：目标目录使用本次结果，其余目录沿用原结果，界面显示局部更新时间。具体规则见[局部重扫方案](docs/snapshot-subtree-rescan-plan.md)。

## 开发环境

| 项目 | 当前配置 |
| --- | --- |
| Flutter / Dart | 3.47.9 / 3.13.7 |
| Android | Android 10+，minSdk 29，compileSdk / targetSdk 36 |
| JDK | 21 |
| Android NDK | 28.2.13676358 |
| CMake | 3.22.1 |
| ABI | arm64-v8a、x86_64 |
| Root 接入 | libsu 6.0.0 RootService，可选 |

### 获取并运行

```powershell
gh repo clone EltanceX/Disksize
cd Disksize

# 使用自己的工具链路径配置当前 PowerShell 终端
. ./tools/enter-android-env.ps1 `
  -FlutterSdk 'C:\path\to\flutter' `
  -AndroidSdk 'C:\path\to\Android\SDK' `
  -Jdk 'C:\path\to\jdk-21'

flutter pub get
flutter devices
flutter run -d '<device-id>'
```

其他终端可直接配置 Flutter、Android SDK 和 JDK 环境变量。本机私有配置可放在忽略提交的 `.local/android-env.ps1`。

### 构建 APK

```powershell
# 日常使用及性能测量使用 Profile 或 Release
flutter build apk --profile --target-platform 'android-arm64,android-x64'

# 开发调试
flutter build apk --debug --target-platform 'android-arm64,android-x64'
```

Profile APK 输出为 `build/app/outputs/flutter-apk/app-profile.apk`。普通构建默认关闭 `disksizeBenchmark` 与 `disksizeRescanTestHost`，Profile 原生扫描核心启用 `-O2`；专用基准及隔离测试宿主由验证脚本显式构建。

当前开发包名为 `com.example.disksize`。应用的 `pubspec.lock`、Gradle Wrapper 和 Pigeon 生成代码均纳入版本管理。修改 `pigeons/scanner_api.dart` 后，运行 `./tools/generate-bridge.ps1` 并一同提交 Dart / Kotlin 生成文件。

## 架构与数据

```text
Flutter 界面
    │ Pigeon / Binder
    ▼
普通 UID 的 :scanner 服务 ── SQLite 索引、聚合、快照和持久任务
    │ JNI                         ▲ 元数据流
    ├── C++ 普通扫描核心            │
    └── 可选 libsu RootService ─────┘
```

C++ 扫描器负责元数据遍历和流式传输，Kotlin 服务负责生命周期、入库、硬链接去重及聚合，Flutter 使用有界查询显示结果。当前 SQLite schema 为 **5**，原生流协议为 **v4**；历史数据通过增量迁移保留。

## 验证与实测

```powershell
flutter analyze
flutter test
./tools/lint-android.ps1

# 局部重扫完整验证：需要已允许 ADB 调试/安装的设备
./tools/snapshot-rescan/verify-step6.ps1 -Serial '<device-id>' -RootInstall -RootTests
```

`-RootInstall` 和 `-RootTests` 仅用于已具备相应授权的设备。完整设备验证包含测试安装和恢复、受控扫描、强杀恢复、存储不足及规模测试，运行前请阅读[验证工具说明](tools/snapshot-rescan/README.md)。

2026-11-19 最近一次 Flutter 验证：**99 项测试通过**，`flutter analyze` 无问题；普通双 ABI Profile 构建、原生优化审计及 Android lint 通过。

以下是 Xiaomi sheng / Android 16 / arm64 上的测量，不代表其他设备或文件分布的固定耗时：

| 场景 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 普通内部存储 100,001 节点完整扫描，中位数 | 16.705 秒 | 6.635 秒 |
| 普通内部存储 1,000,001 节点完整扫描，中位数 | 120.029 秒 | 57.822 秒 |
| `/data/data` 历史快照副本删除，189,695 节点，三轮范围 | 10.23～10.31 秒 | 2.60～2.76 秒 |

扫描数据来自固定样本的完整扫描至终态发布，删除数据来自冻结副本上的删除事务，均不包含 Flutter 页面绘制。测量口径、资源开销及性能边界分别见[扫描性能验收](docs/scan-performance-acceptance.md)、[删除性能优化](docs/snapshot-delete-performance.md)和[局部重扫验收](docs/snapshot-subtree-rescan-acceptance.md)。

## 当前范围

- 仍处于开发阶段，Android 扫描入口最多 2,000,000 个节点、512 层深度；完整目录每页 50 项，查询 API 每页最多 100 项。
- 无法读取的目录保留未知范围；Root 访问仍受 SELinux、加密状态、用户解锁和接收应用权限限制。
- 逻辑大小、报告占用和存储卷可用空间属于不同统计口径。快照空间估算按共享索引库记录分摊，不能视为可清理空间或删除后立即回收的精确字节数。
- 快照是扫描时的记录，外部打开访问设备上的当前内容。重命名在快照对比中按删除和新增展示。
- 卷身份无法确认、规则不兼容或旧索引缺少必要信息时，拒绝增长对比并提示重新扫描。
- 当前真机验证主要覆盖 Android 16、arm64 和 KernelSU；其他系统、设备及 Root 管理器的运行结果需分别验证。

## 文档与工具

- [文档索引](docs/README.md)：设计、阶段实现与验收记录。
- [第一版设计](docs/v1-design.md) · [实施步骤](docs/v1-implementation-plan.md)
- [目录与大文件浏览](docs/phase6-explorer.md) · [矩形树图](docs/phase7-treemap.md)
- [快照与增长对比](docs/phase8-snapshots.md) · [文件夹局部重扫方案](docs/snapshot-subtree-rescan-plan.md)
- [扫描性能基准工具](tools/scan-performance/README.md) · [局部重扫与删除验证工具](tools/snapshot-rescan/README.md)
- [外部打开说明](docs/external-open.md)

本机工具链、原始设备日志、扫描数据库、临时实验及验证产物放在 `.local/`；构建输出、签名材料和 `android/local.properties` 均忽略提交。
