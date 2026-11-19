# 局部重扫步骤 3：数据库合并与重建

日期：2026-11-18。

后续状态（2026-11-19）：界面和日常包均已交付；步骤 6 进一步优化目录成员队列、派生基线重置，并补齐真实 ENOSPC 的异常保留，见[最终验收](./snapshot-subtree-rescan-acceptance.md)。下文为步骤 3 的历史记录。

状态：步骤 3 已实现并完成隔离真机回归。schema 仍为 5，本步没有新增迁移。本文记录数据库合并边界，后续服务、Root 原始路径、Pigeon 与客户端实现见[步骤 4](./snapshot-subtree-rescan-service.md)；目录界面尚未接入。设备日常版仍为之前确认速度正常的优化 Profile 包，真实用户索引没有升级或局部更新。

## 入口与提交边界

实现位于 [SnapshotRescanMerger.kt](../android/app/src/main/kotlin/com/example/disksize/index/SnapshotRescanMerger.kt)，经 `IndexStore.rescans.apply(taskId, signal, cancelled)` 调用。写入仍由普通 UID 的单一索引 worker 负责，不能把本入口直接放在 Binder 或主线程执行。

1. 首先获取快照共享锁并读取持久任务。已有 `committed_revision` 或已经终结的任务直接返回，不再次分配 ID、替换数据或增加局部更新次数。
2. 只有已关闭暂存流、处于 validating 的任务可以进入应用阶段。先单独持久化 applying，旧快照仍保持可用。
3. 随后在一个非排他的 SQLite 写事务内完成复核、成员收集、ID 映射、原始节点替换、全快照派生结果重建、revision 发布、对比缓存失效和提交标记。
4. 一次提交成功后才清理暂存。提交后发布或清理失败仍返回持久化的 completed；启动恢复补清暂存，不能将已经提交的更新改报失败或再次执行。

所选目录 ID、snapshot ID 和 session ID 保持不变。session 与 snapshot 的内容 revision 只增加一次；节点数、问题数、最终状态、根容量、完整性、局部更新时间/次数和任务提交标记在同一事务内更新。原始全量扫描的 started、ended、metrics、volume、identity 及快照 created 不改写。

## 最终校验

合并事务内重新检查快照可用性、expected revision、未保留状态、目标仍为目录、受信任的原始路径与任务记录一致，以及没有全量扫描冲突。

暂存必须满足以下条件，否则保留旧快照并结束任务：

- producer 摘要为 completed，目标存在且仍为目录，零问题；所有节点有效、完整、未跳过，所有目录已枚举。
- 摘要、任务和暂存表中的节点/问题计数一致；暂存 ID 连续且从 1 开始，父节点存在、为目录且先于子节点。
- 原始 BLOB 名称、路径、device/inode 类型及长度正确；名称不含分隔符、NUL、`.` 或 `..`，路径、name key 和深度与父子关系一致。
- 节点类型、数值字段、容量和时间纳秒合法；暂存 device、mount 和摘要 filesystem 与原目标匹配。
- 新子树深度加原目录深度不超过 512，替换后全快照节点数不超过 2,000,000；正式节点数量与原记录一致，最大现存 ID 不超过历史水位。

目标消失、变为文件或符号链接、Partial、挂载变化、异常暂存、stale 或 retained 都不会删除旧子树。同路径目录重建可以更换 inode，但实时用户、后端、卷、启动身份、挂载视图及祖先安全解析仍需步骤 4 的扫描入口校验；本步的数据库校验不能代替这些检查。

## 范围与节点映射

旧范围从选中目录出发，沿 `node_parent(sid,parent,id)` 收集，绝不使用显示路径或字符串前缀判断。成员和映射使用文件侧 TEMP 表，处于最终事务的同一写连接；目录队列每轮最多 256 条，子节点和插入/删除按最多 4096 条处理，不把整树载入 Kotlin 内存。

选中目录保留原 ID、外部 parent、名称、原始相对路径与全局深度，新扫描根提供其余原始元数据。后代 ID 从 session 的历史水位以上分配，通过 TEMP 映射转换 parent；没有变化的后代也不承诺保留旧 ID，已提交使用过的 ID 永不复用。水位预留属于外层事务，失败时一起回滚。

路径拼接保持 BLOB 类型，并将子树内路径转换为快照根相对路径。非 UTF-8 名称、控制字符以及 `a`、`a-`、`a2` 不会混淆。节点删除按成员集合执行，删除旧范围问题；只有替换快照根目录才清除原 node=0 的全局问题。首版要求新暂存零问题，因此不迁入不完整问题记录。

## 派生结果重建

[IndexStore.kt](../android/app/src/main/kotlin/com/example/disksize/index/IndexStore.kt) 新增受外层事务控制的 `rebuildSnapshot`，共享现有硬链接及目录计算方法，但不自行提交、推进 revision 或创建快照。普通全量 finalize 保留原分批事务和发布方式。

重建顺序：

1. 分页清除该 session 的旧 aggregates、hardlinks，以及可重新生成的 `hardlink-metadata-changed` 派生问题。树外原始扫描问题保留。
2. 从合并后的原始 nodes 重新初始化 owner、自身贡献和完整性，不沿用旧的已归零贡献或不可恢复的 false。
3. 重算整个快照的 device/inode 分组和最小原始路径 owner，按既有规则处理元数据冲突。
4. 从深到浅汇总目录，检查整数溢出；原快照其他未知范围继续为未知，已修复范围可以恢复完整。
5. 发布 session/snapshot 统计和 revision，再写提交标记。

owner 应用和初始化均按实际存在的节点 ID 分页，不以节点数量充当最大 ID，支持重复更新后的稀疏 ID 以及接近 `Long.MAX_VALUE` 的合法 ID。目录大范围求和通过带 `CancellationSignal` 的读取执行，随后逐行更新，避免不可取消的宽目录求和写语句。

局部刷新可能改变树外硬链接 owner、贡献及祖先容量，但不改写其原始 nlink、mtime 等元数据。正确性对照仍为“树外旧原始记录 + 子树新原始记录”的完整导入回放；新观察与旧记录冲突时按原规则标为 Partial，不能假装树外也已重扫。

## 并发、取消与失败

[SnapshotQueries.kt](../android/app/src/main/kotlin/com/example/disksize/index/SnapshotQueries.kt) 的所有实例与合并共享同一个快照锁，并统一在获取锁后才开启数据库事务，避免对比构建器与合并的锁顺序反转。提交内同时删除磁盘对比缓存并丢弃标准内存构建器；缓存每批最多删除 4096 行并检查取消，失败时随外层事务恢复。独立构建器下次调用依据缓存和 signature 重新启动，不能写回旧 revision。

目录、详情、大文件、问题及树图查询继续使用 WAL 和查询前后 revision 检查；事务提交前可读完整旧结果，跨提交读取返回 stale-cursor。对比旧游标即使在新缓存已经重建后也保持 stale-cursor，不因 row ID 重用误读新版；不同排序/过滤/页大小的游标仍按非法查询处理。

步骤 3 的内部串行入口允许等待共享锁，普通索引读取不获取此锁。后续步骤 4 已将共享锁改为支持 tryLock 的互斥锁，服务请求在竞争时返回 busy，并将合并放在后台 worker，不持有 Coordinator 状态锁等待。大型快照的锁等待和查询延迟尚未测量。

取消需同时传入可立即触发的 `CancellationSignal` 和线程安全的取消回调。`requestCancellation` 的数据库标记用于持久化意图，但在长写事务中另一个写入请求会等待，不能仅靠轮询这个标记中止合并。步骤 4 必须先通知内存中的 worker/SQL 取消，再完成持久化、停止 producer、关闭 pipe 和状态收尾。

提交前取消、校验失败、容量溢出、SQLite 空间/磁盘错误或其他异常使整次更新回滚，包括原始数据、派生数据、revision、水位、局部更新时间和提交标记。错误按契约映射为 cancelled、stale 或 failed，并尽量清理暂存；真实存储故障若也阻止状态写入，则保留未提交任务，由恢复处理。提交成功后的取消仍以 completed 为准。

任务保留暂存导入指标，并增加 `validationMicros`、`replacementMicros`、`initializedMicros`、`owners_collectedMicros`、`owners_appliedMicros`、`aggregatedMicros` 和新旧子树节点数。这些不是端到端性能验收，尚未记录最终提交、锁等待、WAL 峰值或界面延迟，不能据此承诺耗时只与子树大小成正比。

## 验证结果

复现入口同时回归步骤 1、2 和既有索引/快照测试：

```powershell
. ./.local/android-env.ps1
./tools/snapshot-rescan/verify-step3.ps1 -Serial '<device-id>' -RootInstall
```

2026-11-18，Xiaomi sheng / Android 16 / arm64，`SCAN_BENCHMARK=false`：

- JVM 13 项通过：9 项契约测试及 4 项原 Root 协议测试。
- 真机总计 66 项：65 项通过，1 项 benchmark 专用 `reportImportQueryPlans` 预期跳过，0 失败。其中新增合并测试 20 项。
- 混合时间完整回放对照覆盖新增、删除、修改、空目录、类型替换、选中目录稳定 ID、后代 ID 不复用、原全量时间/指标和其他快照不变。
- 覆盖硬链接 owner 跨范围转移、新最小路径、元数据冲突、冲突修复、树外未知范围保留/修复、根刷新及全局问题清理；四种 finalize 策略分别与各自全量回放一致。
- 非 UTF-8 路径及近似前缀保持准确；接近 `Long.MAX_VALUE` 的稀疏 ID 正常处理，ID/容量溢出和全局深度超限整体拒绝。
- 5101 节点子树含 2250 组成对硬链接，覆盖超过 4096 条的成员/映射/插入/owner 应用/重建，以及超过 256 个目录的队列处理；全部规范化行与回放通过双向 SQL EXCEPT 比较，第二次空刷新可移除已映射的稀疏后代。
- 每个提交前检查点注入异常和取消，逐列核对旧原始数据、派生数据、revision、水位和元数据保持不变；提交后发布失败保持 completed，关闭重开恢复和重复 apply 不重复分配。
- 除模拟空间异常外，还仅对 UUID 测试库设置 `PRAGMA max_page_count`，让实际替换写入产生 SQLiteFullException；验证整体回滚、恢复清理和后续成功更新。未填满设备存储或改变正常库限制。
- 另一线程的 WAL 读取在替换尚未提交时看到完整旧版；旧目录游标、独立内存对比构建器及已重建缓存后的旧对比游标均正确处理。超过 4096 行的真实对比缓存在删除一批后取消，缓存和目标快照均回滚，随后成功更新可完整清空缓存。
- 真实 native producer 经 LocalScanPipe/v4 写暂存后合并，在受控静态树上与真实全扫一致：逻辑 80 字节、14 节点。普通文件及符号链接使用真实文件系统；硬链接和异常字节身份采用确定性原始记录回放。

最终记录：`.local/snapshot-rescan-step3-20261118-04/`，包含构建、instrumentation、正常数据前后指纹、APK 备份及 summary。`-01` 为首轮成功记录，`-02` 补充跨页/真实 SQLite 写满用例，`-03` 增加跨页硬链接，`-04` 另覆盖缓存分页清理与中途取消；最终以 `-04` 为准。

脚本临时覆盖安装当前普通 Debug 主包和测试包，只运行 UUID 专属数据库与目录，不启动 MainActivity/CoordinatorService，不扫描真实 `/data/data`。完成后已恢复原主包和原测试包；正常主库、WAL 和 journal 的 SHA-256 前后一致。恢复的原优化主包 SHA-256：

```text
1ee4d9aeae2a8084a106cb2e6f0061e7d8ecfa3fa513734e254690a4518054d5
```

## 下一步与未验收项

步骤 4 接入异步服务、桥接和普通/Root 原始路径入口，进行实时身份/祖先/挂载核验、启动失败回收、即时取消和 journal 对齐；步骤 5 才增加界面入口及定位刷新。

本步没有对用户库执行局部重扫，没有进行真实进程强杀/断电、物理存储耗尽或大型历史快照性能验收。故障点回滚、数据库关闭重开和页数限制测试不能代替步骤 6 的完整生命周期、资源及交付测试。第一版仍采用全快照 SQL 重算，性能和锁等待必须实测。
