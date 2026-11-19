# 保存快照删除性能优化

2026-11-19：已完成实现、真机验证及普通 Profile 覆盖安装。

## 问题与改动

保存的 `/data/data` 快照约有 19 万个节点。删除需要在同一事务内清理节点、聚合及其排序索引；旧版真机耗时约 10 秒，列表刷新只需几毫秒。Android SQLite 的 `soft_heap_limit=8388608` 会让删除期间的脏索引页反复写出、重新读取。单纯增大 `cache_size`、强制主键查询顺序或将临时表放入内存均没有明显收益。

`SnapshotQueries.withDeletionSpillThreshold` 在删除事务已固定的写连接上，将 `cache_spill` 阈值临时提高到约 32 MiB，保留达到阈值后的写出能力。成功和异常路径均恢复原设置；原来已关闭写出或阈值更大的连接保持原设置。32 MiB 是 SQLite 页缓存的写出阈值，不是整个进程的内存上限。

SQLite 3.44 的数字布尔值会被截成 8 位，直接设置 `cache_spill=8192` 还会意外关闭写出。因此设置数字阈值后显式执行 `cache_spill=ON`；恢复时同样处理。回归特意覆盖原阈值为 4096 的情况。

删除依然在单一事务内完成，沿用原有删除顺序、外键、安全删除、WAL、同步及自动回收设置，不需要 schema 迁移。删除中止或存储错误仍向调用方报告；如果 SQLite 已自动回滚，结束事务的次要异常不会覆盖原始错误。

## 真机测量

设备：Xiaomi sheng，Android 16，arm64，SQLite 3.44.5。输入为已冻结并 checkpoint 的历史库副本：609.2 MiB、8 份快照，选中 `/data/data` 快照含 189,695 节点。每次重新复制同一个输入，旧 SQL 与优化后生产入口交替运行三轮；没有删除手机上的用户快照。

| 轮次 | 旧版删除 ms | 优化后删除 ms | 优化后列表刷新 ms |
| --- | ---: | ---: | ---: |
| 1 | 10292.7 | 2604.1 | 3.2 |
| 2 | 10310.3 | 2615.0 | 2.8 |
| 3 | 10225.6 | 2760.3 | 2.6 |

中位数从 10.293 秒降至 2.615 秒，耗时减少约 74.6%，约为原来的 3.9 倍速度。计时包含删除事务与 COMMIT；优化后调用实际 `snapshot-delete` 入口，包含准入及设置恢复。复制、打开数据库、完整性检查和列表刷新单独计时或排除；这不是 Flutter 点击到动画结束的测量。

同一测试进程每 20 ms 采样 RSS/WAL：旧版 RSS 峰值约 158–163 MiB，优化后约 191–199 MiB；WAL 峰值两者均约 262 MiB。该结果体现了用一定的临时内存换取更少重复写出的取舍，不能视为进程内存硬上限，或保证任意设备、任意规模都在 3 秒内完成。

有限阈值预实验中，16/32/64/128 MiB 分别约为 2.84/2.64/2.55/2.54 秒，因此采用 32 MiB。探索阶段的关闭写出测量不作为最终有限阈值方案的验收证据。

## 正确性与交付

- 30 项设备测试全部通过，包含快照管理/对比、保留保护、活动重扫冲突、历史任务级联清理，以及新增的 ABORT/自动 ROLLBACK 故障、写连接设置恢复与重开检查；JVM 13 项通过。
- 每次测量均检查 `integrity_check`、`foreign_key_check` 和目标索引清理。最后一份结果库与“在另一副本执行旧版删除 SQL”得到的预期库逐表逐列摘要一致，剩余 7 份快照、240,491 条节点/聚合完整保留，包含原始 BLOB。
- 测试安装与恢复前后，正常库/WAL/journal 的 SHA-256 一致。普通包覆盖安装并冷启动后，再比较正常库所有表及 journal：原有 7 份快照、240,492 条节点/聚合、4 条重扫历史均一致；schema 仍为 5。
- 普通 Debug/Profile 双 ABI 构建通过；两个测试开关均关闭，Profile native `-O2` 及 build ID 审计通过。Android lint 0 错误、8 条现有警告；`git diff --check` 通过。

已安装的普通 Profile APK SHA-256：`00577fcb3ade4e263d420740224132d03daebe816c1500f598687219879dbaff`。安装后已核验设备包哈希并重新打开应用。

主要证据保存在 `.local/snapshot-delete-final-20261119-01/`：`delete-performance.json`、`instrumentation.log`、`delete-data-verification.json`、`native-audit/`、`lint.log` 和 `delivery/verification.json`。旧包及安装前后正常数据备份保存在该目录，未用历史测试副本覆盖正常库。

## 复现

```powershell
. ./.local/android-env.ps1
./tools/snapshot-rescan/verify-step2.ps1 `
  -Serial '<device-id>' -RootInstall -DeleteOnly `
  -DeleteFixture 'C:\path\frozen\scan-index.db' `
  -DeleteSnapshot '<副本内未保留的快照 UUID>' `
  -DeleteVariants 'legacy,production' -DeleteRepeats 3 `
  -Output '.local/snapshot-delete-new-run'
```

输入必须是已停止写入、完成 checkpoint 的独立 SQLite 文件，不可直接指定正在使用且依赖 WAL 的库。脚本备份/恢复原 APK，检查正常数据指纹；仅把副本流式写入固定测试文件，测试逐次使用 UUID 副本，测试结束清理固定文件。`spill16,spill32,spill64,spill128` 可单独比较阈值。该入口只验证并恢复原安装，正式交付仍使用普通 Profile 覆盖安装工具。
