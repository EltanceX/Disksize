# 快照局部重扫验证

## 大快照删除性能

`verify-step2.ps1 -DeleteOnly -DeleteFixture '<冻结并 checkpoint 的独立 SQLite 文件>' -DeleteSnapshot '<副本中的 UUID>' -DeleteRepeats 3` 会交替测量旧 SQL 与生产删除入口，同时运行快照管理和任务持久化回归。仍需传入 `-Serial`，按设备安装环境选择 `-RootInstall`；该模式与局部重扫宿主套件分开运行。

所有删除都发生在 UUID 副本内，脚本核对正常数据并恢复原 APK。测量与交付说明见[删除性能优化](../../docs/snapshot-delete-performance.md)。默认 `DeleteVariants=legacy,production`；可使用 `spill16,spill32,spill64,spill128` 比较有限写出阈值。

## 步骤 6：最终验收与普通包

```powershell
. ./.local/android-env.ps1
./tools/snapshot-rescan/verify-step6.ps1 -Serial '<device-id>' -RootInstall -RootTests
```

完整结果及边界见[步骤 6 验收](../../docs/snapshot-subtree-rescan-acceptance.md)。入口串行执行 Flutter、核心/全量生命周期/外部打开、强杀与百万节点验收、受限文件系统 ENOSPC、native 和 UI 集成，再构建关闭两个测试开关的普通双 ABI Debug/Profile，审计 Profile APK 的 -O2 和 build ID，最后执行 lint。隔离测试均备份并恢复原 APK、核对正常数据，脚本不会自动覆盖部署日常包；本轮已另行完成正常 Profile 安装、旧库逐表校验和日常界面操作。

`verify-enospc.ps1` 要求现有 Root 授权及 BusyBox mount，默认 `/data/adb/ksu/bin/busybox`，可用 `-Busybox` 指定其他安装位置。它只填充 private mount namespace 内的 12 MiB tmpfs，以普通应用 UID 执行真实合并，不填满设备分区。须先构建隔离宿主主 APK 和测试 APK。`audit-normal-apk.ps1` 核对正常 Profile 的两个 ABI；`check-delivery-data.py` 在停止应用后取得的数据库/WAL 副本上比较迁移前所有列，支持 BLOB，不读取手机上的活动数据库。

单独运行最终新用例：`verify-step2.ps1 -IncludeService -AcceptanceOnly`；完整旧功能回归增加 `-IncludeMerge -IncludeRegression -RootTests`。`AcceptanceMethods` 可选择强杀、合并取消或规模方法。性能端到端包含采样退出，务必同时查看任务分阶段时间、COMMIT、查询延迟和采样口径。

## 步骤 5：目录与快照界面

```powershell
. ./.local/android-env.ps1
flutter analyze
flutter test
./tools/snapshot-rescan/verify-step5.ps1 -Serial '<device-id>' -RootInstall -RootTests
```

实现及结果见[步骤 5 记录](../../docs/snapshot-subtree-rescan-ui.md)。入口先运行步骤 2/3/4 的 JVM 和设备回归，再单独构建 `integration_test/snapshot_rescan_test.dart` 并执行真实 Flutter 目录重扫流程。两次均使用固定 DE 测试库，备份/恢复原主包和测试包，核对正常数据指纹。UI target 不与服务测试同时启动；专用 Android 用例在启动 Activity 前核验测试宿主和 instrumentation 参数，Dart 入口也要求编译标记。

仅重跑 UI：`./tools/snapshot-rescan/verify-step2.ps1 -Serial '<device-id>' -RootInstall -IncludeService -UiOnly`。不要将该集成入口直接作为普通应用部署；普通 Debug/Profile 构建应关闭 `disksizeRescanTestHost` 和 `disksizeBenchmark`，使用默认 `lib/main.dart`。输出目录默认 `.local/snapshot-rescan-step5-<UTC时间>/`。

## 步骤 4：扫描服务与桥接

当前完整实现优先使用以下入口：

```powershell
. ./.local/android-env.ps1
flutter analyze
flutter test
./tools/snapshot-rescan/verify-step4.ps1 -Serial '<device-id>' -RootInstall -RootTests
```

实现与结果见[步骤 4 记录](../../docs/snapshot-subtree-rescan-service.md)。脚本构建关闭 benchmark 的普通优化链路和仅 debug 的 `RESCAN_TEST_HOST=true`，服务库/journal 固定隔离到 DE 的 `snapshot-rescan-service-test-v1.*`，不打开用户正常库。执行前 3 步回归及 `SnapshotRescanServiceTest`，再双 ABI 构建 native 测试并在设备 ABI 上运行。默认输出 `.local/snapshot-rescan-step4-<UTC时间>/`；支持 `-Output` 和显式确认产物一致时的 `-SkipBuild`。

`-RootTests` 要求应用和 ADB shell 已有本设备的 Root 授权，不自动修改管理器策略。它执行真实 Root 生产者、原始异常字节、取消与经 UID/cmdline 核验后的 Root 进程强杀，也回归原 native bind mount 测试，挂载仅在测试子进程的私有 namespace 内进行。不传该开关时跳过 2 项 Root 服务用例，native 只运行范围安全及 pipe 套件。`-RootInstall` 仅控制安装/恢复方式。

服务测试需要设备解锁并可将测试 Activity 置于前台；复用已有 foreground helper，不修改设备电源、权限或前台服务时限。所有扫描和删除都限于 UUID 自有样本；杀普通 Coordinator 前核对进程名、PID、UID。测试期间请勿操作应用或发起自己的扫描。

本入口继承下方 APK 备份、正常数据指纹和 finally 恢复保护。服务测试之后先停止应用并恢复原主包/测试包，再按固定文件名清理测试 DE 库。不会卸载主应用、清空用户数据、扫描真实 `/data/data` 或留下测试宿主替代日常包。强制终止主机进程仍可能使 finally 无法完成，必须保留本次原 APK 备份。

完整回归实测为 Flutter 69 项、JVM 13 项、设备 75 项通过及 1 项预期跳过、native 35 项通过。默认 `RESCAN_TEST_HOST=false` 的普通 Debug 也单独回归原数据库套件。该测试开关在 Profile/Release 始终为 false，不能把测试包作为功能交付包；目录界面和日常包交付分别属于步骤 5、6。

## 步骤 3：数据库合并与重建

只验证数据库合并时使用此入口，同时回归步骤 1、2 和原索引/快照测试；完整服务验证请用上方步骤 4：

```powershell
. ./.local/android-env.ps1
./tools/snapshot-rescan/verify-step3.ps1 -Serial '<device-id>' -RootInstall
```

实现与结果见[步骤 3 记录](../../docs/snapshot-subtree-rescan-merge.md)。入口复用步骤 2 的构建、APK 备份、隔离测试和恢复保护，额外运行 `SnapshotRescanMergeTest`；默认输出 `.local/snapshot-rescan-step3-<UTC时间>/`，支持 `-Output`、`-SkipBuild`。普通构建最终回归为 JVM 13 项通过、设备 65 项通过及 1 项预期跳过。

合并测试仅更新 UUID 测试库，包含混合时间回放、真实 native 静态树对照、5101 节点跨页合并、稀疏 ID、取消/异常回滚、提交恢复及对比游标。空间不足用专属库的 SQLite 页数限制触发，不填满设备或修改正常数据库。测试不等于服务/Root/界面集成或大快照性能验收。

## 步骤 2：schema 与持久任务

只需回归持久化层时使用此入口；验证当前完整数据库合并请使用上方步骤 3 入口：

```powershell
. ./.local/android-env.ps1
./tools/snapshot-rescan/verify-step2.ps1 -Serial '<device-id>' -RootInstall
```

实现与结果见[步骤 2 记录](../../docs/snapshot-subtree-rescan-persistence.md)。schema 已升到 5，不能仅替换测试 APK 后依赖旧主包验证生产代码。

脚本构建 JVM 测试、关闭 benchmark 的普通 Debug 主 APK 和测试 APK；拒绝中断活动扫描，备份设备原主包与测试包，记录正常数据指纹。临时覆盖安装后，仅执行 `SnapshotRescanStoreTest`、`SnapshotRescanBaselineTest`、`SnapshotStoreTest`、`IndexStoreTest`，不启动主界面或扫描服务，正常索引不会被打开。结束时核对正常数据未变，再覆盖恢复原优化主包与原测试包，逐一检查 SHA-256；无原测试包时只卸载新测试包。

测试期间请勿打开应用。若正常库/WAL/journal 意外变化，脚本拒绝恢复旧主包，以免导致 schema 降级；应先检查变化，而非覆盖正常数据库。若主机进程被强制终止或设备断开，finally 可能无法完成，保留输出目录中的 `previous-main.apk` / `previous-test.apk` 和指纹，确认正常数据未变化后用 `tools/install-android-apk.ps1` 覆盖恢复。脚本不会卸载主应用或清空用户数据。

输出默认为 `.local/snapshot-rescan-step2-<UTC时间>/`，支持 `-Output` 指定新目录；`-SkipBuild` 仅用于明确确认所有产物和单测报告与当前源码一致的情况。summary 区分真机总数、通过与跳过，benchmark 专用查询计划测试在普通构建跳过是预期结果。仅支持单 APK 的可调试安装；`-RootInstall` 只控制安装方式，不改变扫描权限。

## 步骤 1：契约与回归基线

以下保留步骤 1 时 schema 4 的历史运行方式。当前 schema 5 的生产代码和测试已变化，优先使用上方步骤 3 入口，不应再用仅替换测试包的方式验证旧日常主包。

设计与实际结果见[步骤 1 契约](../../docs/snapshot-subtree-rescan-contract.md)，完整路线见[实施方案](../../docs/snapshot-subtree-rescan-plan.md)。

需要 PowerShell、项目 Android 开发环境、已连接设备及已安装的兼容普通开发包。当前验证使用协议 v4、schema 4、普通 UID 索引以及已安装优化版中的扫描核心。

```powershell
. ./.local/android-env.ps1
./tools/snapshot-rescan/verify-step1.ps1 -Serial '<device-id>' -RootInstall
```

`-RootInstall` 仅使用现有 ADB Root 权限安装/恢复测试 APK，不切换实际扫描后端，也不创建真实硬链接或更改 Root 授权、SELinux 策略。没有该权限时可省略，由设备的正常 ADB 安装流程处理。

验证脚本：

1. 执行 JVM 测试并构建 debug 测试 APK，不安装主 APK。
2. 拒绝中断正在进行的用户扫描，备份设备原测试 APK，记录主 APK 指纹。
3. 暂停主应用后，记录正常索引主库、WAL 和 journal 指纹。
4. 临时安装测试 APK，只运行 `SnapshotRescanBaselineTest` 和既有 `SnapshotStoreTest`。
5. 无论测试通过或失败，均尝试恢复原测试 APK，并核对主 APK 与正常快照数据未变；如原先未安装测试包，则仅卸载本次测试包。
6. 成功时输出 summary.json；构建日志、instrumentation 日志及恢复备份保留在忽略目录。

默认输出目录为 `.local/snapshot-rescan-step1-<UTC时间>/`；可用 `-Output` 指定新目录，已有目录会被拒绝覆盖。`-SkipBuild` 仅用于明确确认测试产物与 JVM XML 报告均为当前代码的情况。

正常测试只操作带 UUID 的专属目录和专属数据库，不扫描真实 `/data/data`，不创建用户快照，也不更改主应用版本。强制终止主机进程或断开设备可能使 finally 来不及恢复；应保留本次目录里的 `previous-test.apk`，重新连接后用已有安装工具覆盖恢复测试包。

## 分层运行

只运行 JVM 契约测试，无需设备：

```powershell
. ./.local/android-env.ps1
./android/gradlew.bat -p ./android :app:testDebugUnitTest `
  --tests com.example.disksize.scan.SnapshotRescanContractTest -PdisksizeBenchmark=false
```

真机层使用独立 `rescan-baseline-<UUID>.db` 和 `files/rescan-baseline-<UUID>/`，测试结束删除这些自有样本。native 树使用普通文件及符号链接；硬链接身份、原始异常名称和混合时间元数据通过确定性完整元数据回放覆盖。

基线规范化仅用于受控小样本，不是生产导出 API。它排除本次扫描 ID 和采集时间，但保留文件 mtime、原始字节名称、元数据、容量、owner 路径及完整性；修改前旧快照仍保持不变。
