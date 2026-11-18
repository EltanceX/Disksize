# 扫描性能基准

首次优化构建的真机结果见[基线报告](../../docs/scan-performance-baseline.md)，批量提交的对照见[导入改进报告](../../docs/scan-performance-import.md)。

在已安装开发版 DiskSize 的测试设备上运行。需要 PowerShell 7、项目的 Android 开发环境和 ADB 调试。Root 用例需要开发包已有 Root 授权；`-RootInstall` 和 `-SampleResources` 另需要 ADB shell 已有 Root 权限。两者不更改 Root 管理器策略。

```powershell
. ./.local/android-env.ps1
./tools/scan-performance/verify.ps1 -Serial '<device-id>' -Suite Smoke
./tools/scan-performance/verify.ps1 -Serial '<device-id>' -Suite Baseline -RootInstall
./tools/scan-performance/verify.ps1 -Serial '<device-id>' -Suite Custom `
  -Size 100000 -Mode A,B,C -Backend root -View emulated-direct -RootInstall
```

- `Smoke`：10,001 节点、普通 UID、A/B/C/Replay 各一次，用于检查结果，不当作性能基线。
- `Baseline`：先冒烟，再测内部文件系统的 100,001 节点普通/Root 同树扫描及普通回放，外部同树的普通 FUSE、Root FUSE、Root 直达，以及内部 1,000,001 节点普通扫描。每个用例默认预热 1 次、采集 5 次；每轮轮换用例顺序。
- `Custom`：通过 `-Size`（不含根节点）、`-Mode`、`-Backend`、`-View`、`-Warmups` 和 `-Repeats` 选择一组用例。`Replay` 仅使用普通 UID，`emulated-direct` 仅用于 Root。

固定样本的 C 和 Replay 可用 `-ImportVariant` 在同一个 APK 中逐轮交错对照导入策略。省略时使用应用默认策略 `4096-eager-list`。

```powershell
./tools/scan-performance/verify.ps1 -Serial '<device-id>' -Suite Custom -Size 100000 `
  -Mode C,Replay -ImportVariant 256-eager-list,4096-eager-list -RootInstall
```

策略名称依次指定事务动作上限（`256`、`1024` 或 `4096`）、进度提交方式（`eager` 每个进度帧尝试提交，`paced` 可合并并检查 250 ms 活动窗口）和绑定方式（`list` 或 `typed`）。后缀 `-lean` 仅让本次独立测试库省略 `node_parent` 索引。`paced` 只在收到导入动作或进度帧时检查时限，不会在 pipe 无数据或 SQLite 阻塞时从另一个线程强行提交。变体记入逐次 JSON 和分组汇总，对正式库与 APK 的默认策略无影响。

同一入口可对照[最终处理策略](../../docs/scan-performance-finalize.md)。`-FinalizeVariant` 支持 `baseline`（原策略）、`dedup`（仅改去重）、`directories`（仅改汇总）、`optimized`（两项启用，应用默认）。可以指定一个固定的 `-ImportVariant`，两种策略维度不会同时变化。

```powershell
./tools/scan-performance/verify.ps1 -Serial '<device-id>' -Suite Custom -Size 100000 `
  -Mode C -FinalizeVariant baseline,dedup,directories,optimized -RootInstall
./tools/scan-performance/verify.ps1 -Serial '<device-id>' -Suite Custom -Size 100000 `
  -Mode Replay -FinalizeVariant baseline,optimized -ReplayFixture dense-links -RootInstall
```

`-ReplayFixture` 默认为 `empty`。`sparse-links` 和 `dense-links` 跨目录构造不同密度的硬链接、晚发现的 owner 及同 inode 元数据变动；预期状态为 `partial`。`deep-wide` 同时构造深目录链和宽目录。非空配方还会在计时结束后验证容量和状态，并校验各行 owner、容量、完整性及问题记录摘要一致。真实文件的 C 仍使用空文件树。

固定样本的 A/B/C 可以用 `-PipelineVariant` 单独对照 [native 与传输策略](../../docs/scan-performance-native.md)：`baseline` 是分两次查元数据、逐帧分配，`metadata` 仅合并查询，`transport` 仅复用编解码缓冲，`optimized` 两项都启用（默认）。对照组固定使用默认导入和最终处理策略；固定样本的容量、节点种类、状态等必须一致。模式 A 不经过传输，因此只需对比 `baseline` 与 `optimized`。

```powershell
./tools/scan-performance/verify.ps1 -Serial '<device-id>' -Suite Custom -Size 100000 `
  -Mode B -PipelineVariant baseline,metadata,transport,optimized -RootInstall
./tools/scan-performance/verify.ps1 -Serial '<device-id>' -Suite Custom -Size 100000 `
  -Mode A -PipelineVariant baseline,optimized -MeasureMetadata -RootInstall
```

`-MeasureMetadata` 仅模式 A 可用。`namedMetadata` 统计按目录 FD 和名称发起的 `fstatat`、`statx` 次数与各自累计耗时，以及需打开 `O_PATH` 再确认挂载的回退次数和耗时。不含根目录、打开的目录 FD 核验或结束检查；回退内部调用合在 `fdFallbackMicros`。此选项增加逐项计时，应单独运行，不与无诊断的吞吐结果混算。

| 模式 | 实际执行 | 主计时 `elapsedMicros` |
| --- | --- | --- |
| A | APK 中的 `stream_directory`，回调仅累加 kind、深度和原始容量；无 pipe/SQLite | native 核心经过时间 |
| B | 同一核心、v4 帧、真实 pipe、原有严格解码和有界计数消费者 | 后端派发至完整流校验、关闭 |
| C | 真实 Binder → 前台 `CoordinatorService` → native pipe → SQLite 去重/聚合 → journal；固定样本验证大文件首末页 | 测量契约中的 `prepareToPublishMicros` |
| Replay | 以固定顺序生成合成节点/目录关闭事件，直接调用真实 `IndexStore.Import`/`finalize` | 开库、生成/写入事件、聚合及预览；没有文件遍历、pipe 或 journal |

A、B 的容量是去重之前的原始统计；C 是最终快照统计。Replay 的事件顺序、inode、容量及路径名固定，不捕获真实路径，且不模拟生产者进度带来的额外提前提交。真实与合成样本都按 `empty-tree-v1` 配方生成，报告用 `recipeSha256` 标识配方并核对节点类型计数、容量和完整性；这不是扫描所有文件内容所得的哈希。

## 构建与输出

工具用 Gradle 构建 Flutter Profile + instrumentation APK，显式给 native 核心与 JNI 应用 `-O2 -g -DNDEBUG`。Profile 的 Android 工程仍可调试，不能当作 Android Release，也不能直接和历史 Debug/旧 schema 结果做加速对照。

`-SkipBuild` 复用近期生成的两个 APK，但仍核对双 ABI 库。工具匹配 APK 内 ELF 与未剥离库的 build ID，核对 `compile_commands.json` 中扫描源文件最后的 `-O` 参数、调试节与链接命令；审计结果和符号随报告保留。

输出默认在忽略提交的 `.local/scan-performance/run-*/`；`-Output` 可选一个新的本地目录。`environment.json` 记录提交、工作区状态、APK 哈希、设备和构建口径。每次运行保存 `*.json` 和 instrumentation 日志，`summary.json` 给出有效重复的中位数和最小/最大值，不含预热。设置/清理、Root 就绪和 Binder 连接单列，已采集字段见[测量契约](../../docs/scan-performance-measurement-contract.md)。

`-SampleResources` 可独立开启约每秒的 Linux 进程采样，记录 CPU ticks、RSS/HWM/PSS、打开的 FD 数和 PID 启动 ticks，并读取独立基准库主库与 WAL 的文件长度。按设备时间窗和 `(PID, start_ticks)` 关联，ticks 除以 `clockTicksPerSecond` 得到样点间的 CPU 秒。同一时刻不同进程行的库文件长度相同；应取最大值而非相加，采样峰值是下界。空列表示未取到样本；短用例可能没有两个样点，不可填零或据此推算 CPU 百分比。采样会影响负载，应和无采样运行分开报告。没有主动清空页面缓存，预热后仍可能受内存回收和温度变化影响。

测试样本只在带归属标记的 app 私有目录和 app 专属外部目录生成与删除。FUSE 与直达对照使用同一批外部样本，通过 `/storage/emulated/<user>` 和其 Root 可见的 `/data/media/<user>` 入口分别扫描，核对各自的文件系统 magic。完整链路与回放使用设备保护存储中的独立基准数据库和日志，每次试验前重建，避免已保留快照大小影响结果。运行期间需要用基准 APK 覆盖安装开发包；脚本先检查普通扫描是否正在运行，保留已安装 APK，在结束或失败时恢复。不会卸载应用或清理原库。

真实 `/data/data` 仅通过以下显式选项单独运行，它采集应用元数据且允许 Partial；不会加入固定树汇总：

```powershell
./tools/scan-performance/verify.ps1 -Serial '<device-id>' -Suite Custom `
  -Mode C -Backend root -LiveAppData -Repeats 1 -Warmups 0 -RootInstall
```

此报告不输出真实应用路径列表。若主机进程被强制结束，自动恢复可能来不及执行；原安装包在本次目录的 `restore-app.apk`，可通过现有覆盖安装工具恢复。清理测试目录前需要核对对应的本次归属标记。
