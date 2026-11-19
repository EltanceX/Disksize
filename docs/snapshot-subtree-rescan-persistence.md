# 局部重扫步骤 2：schema 与持久任务

日期：2026-11-18。

状态：步骤 2 已实现。本文记录本步完成时的普通 UID 持久化边界；后续实现见[数据库合并与重建](./snapshot-subtree-rescan-merge.md)与[扫描服务与桥接](./snapshot-subtree-rescan-service.md)。目录界面尚未接入。日常设备仍运行之前确认速度正常的优化 Profile 包，真实索引没有升级。

## 数据结构

`IndexStore.SCHEMA_VERSION` 从 4 升到 5，保留 schema 1/2/3/4 的逐段增量迁移，无删库、重建用户快照或伪造历史扫描身份。

| 表与字段 | 语义 |
| --- | --- |
| `sessions.node_id_high_water` | 本 session 已提交分配的最大节点 ID；升级时从 `MAX(nodes.id)` 回填，而非从节点数量推断 |
| `snapshots.local_updated`、`local_updates` | 最近局部更新时间与次数，历史快照默认 0；不改写原始 started、ended、created |
| `rescan_jobs` | 独立 task ID、snapshot/session ID、目标节点与原始相对路径、expected revision、状态、取消请求、创建/开始/扫描结束/任务结束时间、错误、提交 revision、计数、指标、扫描摘要和卷信息 |
| `rescan_nodes` | task ID 隔离的原始节点和目录结束状态；与正式节点采用相同 BLOB、元数据和路径语义，不创建普通 session 或 snapshot |
| `rescan_issues` | task ID 隔离的问题记录，包括允许 node=0 的全局问题 |

任务状态使用[步骤 1 契约](./snapshot-subtree-rescan-contract.md)。数据库 CHECK 阻止没有提交标记的 completed 状态；部分唯一索引保证最多一个活动局部任务。任务外键随所属快照删除，暂存随任务删除；选中目录本身没有外键限制，以允许将来的事务内替换。

迁移保留原有快照、revision、保留标记、原始节点/问题/聚合、卷及扫描身份和对比缓存。旧 schema 未记录的身份仍为未知，不因此放宽后续重扫兼容性校验。

## 持久化接口

实现位于 [SnapshotRescanStore.kt](../android/app/src/main/kotlin/com/example/disksize/index/SnapshotRescanStore.kt)，经 `IndexStore.rescans` 访问，尚非对外服务 API。

- `create(taskId, request)`：事务内从索引读取真实 snapshot/session、目录与原始路径，校验可用性、保留状态、expected revision 和任务冲突。调用方不能传任意路径；实时卷/挂载/后端身份校验仍属于步骤 4。
- `beginStaging(taskId)`：推进 scanning，并返回复用现有 `IndexConsumer` / v4 协议的有界写入器。
- `Staging.finish(summary)`：核对接收计数，提交最后批次并保存摘要，推进 validating；这不代表结果已通过完整合并校验，Partial 或问题结果仍须在后续步骤拒绝。
- `transition(...)`：使用契约状态机推进阶段或结束失败任务；completed 必须走提交标记。终态不能重开，未完成批次必须先 flush 或 close，避免状态写入意外嵌套在导入事务内。
- `requestCancellation(...)`：只记录取消请求。生产者停止、写入器关闭、事务回滚后，再由 worker 发布 cancelled；已有提交标记时返回 already-completed。
- `cleanStaging(taskId)`：仅清理该终态任务的大数据，保留计数、摘要和轻量历史；禁止清理活动任务。失败终态自动清理，成功提交后可显式清理，启动恢复也会补清。
- `recover()`：未提交活动任务转 interrupted，已提交任务以 durable revision 为准转 completed；重复执行不会重复应用更新或推进快照 revision。`IndexStore.recover()` 已调用该恢复逻辑，不使用会破坏目标快照的普通 `interrupt()`。

暂存写入采用 typed bindings、最多 4096 动作或约 250 ms 的批次、最多 32 个目录且总路径字节不超过 1 MiB 的缓存；缓存淘汰后通过任务内的父 ID 查询原始 BLOB 路径。关闭写入器回滚未提交尾批，已提交批次等待任务清理。没有把完整树加载到内存，也没有经 JSON 传输整棵树。

所有写入仍要求由普通 UID 的索引 worker 串行调用。数据库层已提供活动任务唯一约束、全量导入与局部任务互斥，以及目标快照删除/保留变更返回 busy 的保护；前台服务启动、Binder 错误映射和完整调度锁属于步骤 4。

## 步骤 3 的事务约束

`allocateNodeIds(db, sessionId, count)` 与 `recordCommit(db, taskId, revision, metrics)` 只接受当前 store 的调用方持有事务，不自行提交：

1. 开启最终事务，再次校验目标状态、expected revision、取消及兼容性。
2. 分配高于水位的后代 ID，替换目标子树并重建派生结果。提交过的 ID 不再复用，未提交事务的预留可随整体回滚；计数为 0 不分配，溢出在写入前拒绝。
3. 将 session/snapshot revision 同步推进一次，更新容量、完整性和计数，使对比缓存失效。
4. 调用 `recordCommit`：确认两份 revision 均为 expected+1、快照可用且未保留，写提交标记和局部更新次数/时间，并保留暂存阶段的导入指标、补充合并指标。
5. 外层一次提交，再发布状态、清理暂存。同一 task/revision 重复记录返回 false，不重复增加次数；提交后的 marker 先于调用方其他重放动作检查。

本步的提交测试只模拟 revision 发布以验证持久化原子性，不执行真实子树替换。完整性校验、取消中的长 SQL、硬链接和祖先汇总、游标与内存对比构建器失效都仍待步骤 3 实现。不能把当前普通 `finalize()` 直接用于局部合并，也不能将节点数量当成稀疏 ID 上限。

## 可见性与空间

快照列表、最近扫描和目录查询仍只读取原正式表；任务及暂存不会成为一份新快照或最近扫描。快照 JSON 新增 `lastLocalUpdateAtMillis` 和 `localUpdateCount`，本步未接界面展示。

存储估算仍是共享数据库已用页按记录数分摊的近似值。分母包含未清理暂存记录和轻量任务历史，避免把全部暂存开销只分给正式快照；不保证精确归属，也不等于可回收空间。局部扫描仍需额外暂存空间，清理仅释放可重用页，不自动 VACUUM。

## 验证记录

最终复现入口：

```powershell
. ./.local/android-env.ps1
./tools/snapshot-rescan/verify-step2.ps1 -Serial '<device-id>' -RootInstall
```

2026-11-18，Xiaomi sheng / Android 16 / arm64 设备，`SCAN_BENCHMARK=false`：

- JVM 13 项通过：9 项重扫契约及 4 项既有 Root 协议测试。
- 真机总计 46 项：45 项通过，1 项 `reportImportQueryPlans` 因只适用于 benchmark 构建而按预期跳过，0 失败。
- 其中本步新增 `SnapshotRescanStoreTest` 13 项；其余为 4 项步骤 1 基线、14 项快照回归、15 项原索引测试（含上述跳过项）。
- schema 1/2/3/4 分别用独立旧库升级，逐列比较全部既有记录；验证 retained、卷/身份、对比缓存、原始异常字节、稀疏 ID 37、水位回填、历史列表/目录浏览及外键。
- 验证 preparing/scanning/validating/applying 中断恢复、失败/取消/stale 终态、任务互斥、按 task 清理和删除级联、marker 幂等、尾批回滚、revision/水位/更新时间整体提交或回滚，以及保留原全量时间。
- 复用真实 native producer / `LocalScanPipe` / v4 parser 写暂存；4200 节点样本覆盖批处理与目录缓存淘汰；原有快照内容与 revision 保持不变。

最终记录目录：`.local/snapshot-rescan-step2-20261118-04/`，包含构建、instrumentation、正常数据前后指纹、APK 备份及 summary。早期 `-01` 为首轮 43 项测试，`-02`/`-03` 为补充测试与统计脚本迭代记录；最终以 `-04` 为准。

验证期间临时覆盖安装当前普通 Debug 测试宿主与测试 APK，仅运行 UUID 专属数据库/样本，不启动 MainActivity 或 CoordinatorService，不扫描真实 `/data/data`。完成后恢复原主包和原测试包；正常主库、WAL 和 journal 的 SHA-256 前后一致。恢复的优化主包 SHA-256：

```text
1ee4d9aeae2a8084a106cb2e6f0061e7d8ecfa3fa513734e254690a4518054d5
```

本步没有进行真实用户库升级、实际杀进程/断电、磁盘写满或大快照合并性能验收。恢复测试使用数据库关闭重开与显式不提交事务；这些不能代替后续完整故障矩阵。
