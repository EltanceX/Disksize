# 局部重扫步骤 6：正确性、性能与交付验收

日期：2026-11-19。

状态：步骤 1–6 已完成。最终普通优化 Profile 已覆盖安装，两个测试开关关闭；原有 8 份快照和 430,186 条节点记录完整保留。本次自有样本及其两份全量测试快照已清理，原最近扫描 journal 已恢复。

设备：Xiaomi sheng，Android 16，arm64；schema 5，流协议 v4，普通 UID 持有 SQLite。本文区分受控隔离测试与日常安装后的人工界面验收。性能数字来自真实 native 扫描生成的静态文件树；不扫描用户真实应用目录来制造基准，也不据此承诺其他设备或动态 `/data/data` 的速度。

## 本轮发现与修正

1. 成员队列原先每 256 个成员继续查询，文件叶子页也会对历史节点索引执行父节点查询。百万历史节点中的大子树超过 180 秒等待上限。现在队列只推进目录，保留原始父子关系、范围及节点映射规则。
2. 原派生重建先删除全快照 aggregates，再重新插入全部行。百万快照刷新极小目录时，观察到数百 MiB 的 WAL。现在仍检查全部原始节点，分页删除已经失效的 aggregate ID，只替换与原始基线不相等的派生行；硬链接 owner 和目录汇总仍在同一个事务内全局重算。已符合原始基线的单独文件无需重写所有二级索引，硬链接贡献与修复后的完整性也仍会从原始值重新建立。
3. 真实 ENOSPC 会让 SQLite 自动回滚，Android 再执行 ROLLBACK 时抛出“no transaction is active”，原先会掩盖最初的 SQLiteFullException。现在保留原始异常，将后续回滚异常附加为 suppressed，正确报告 storage-error。
4. 最终包审计发现 CMake 的自定义 lowercase profile 配置没有默认优化参数，原先只有打开 benchmark 才显式使用 -O2。现在普通 Profile 始终使用 -O2 -g -DNDEBUG，benchmark 宏仍只在基准包启用。两个 ABI 的 APK 内库与当前编译产物 build ID 已匹配；最终 APK 已重新覆盖安装并再次通过日常界面重扫。

修改位于 [SnapshotRescanMerger](../android/app/src/main/kotlin/com/example/disksize/index/SnapshotRescanMerger.kt) 和 [IndexStore](../android/app/src/main/kotlin/com/example/disksize/index/IndexStore.kt)。正式构建不启用测试检查点。

## 正确性与恢复矩阵

| 范围 | 证据 |
| --- | --- |
| 新增、删除、改容量、空目录、类型变化、根替换 | 基线及 MergeTest 与混合时间完整回放/静态 native 全扫对照 |
| 近似前缀、深路径、非 UTF-8、稀疏/单调 ID | StoreTest、MergeTest、原始路径定位与 Flutter 长路径测试 |
| 硬链接 owner 转移、冲突、树外别名、完整性修复 | MergeTest，全局重建结果与参考一致 |
| 目标消失、符号链接、祖先更换、挂载/身份变化 | 普通/Root ServiceTest 及 native 私有命名空间边界测试 |
| 取消与失败 | 每个合并检查点的回滚测试；真实 producer 取消及合并中取消；快照、revision、水位不变 |
| 空间不足 | SQLite 页数限制及本轮 12 MiB 独立 tmpfs 的真实 ENOSPC；旧记录、revision、水位、完整性校验通过 |
| 强杀恢复 | OLD_REMOVED、BEFORE_COMMIT、AFTER_COMMIT 真正 SIGKILL 独立 Coordinator；前两点恢复完整旧版，后一点恢复完整新版，重启两次不重复应用 |
| 并发和查询 | 合并挂起时可读旧版；冲突写返回 busy；旧游标失效；磁盘及内存对比构建器失效 |
| 旧库与历史 | schema 1–4 升级、保留保护、另一快照不改写、journal 不串会话 |
| 界面及回归 | Flutter 入口、确认、任务重连、定位、混合时间、宽屏/大字号；全量扫描生命周期、快照管理、外部只读打开及系统 Files 导航 |

真实强杀/取消使用 debug 隔离宿主的固定 DE 库。检查点仅接受该宿主自己的任务 ID；普通/Profile/Release 的 RESCAN_TEST_HOST 均关闭，没有通过 Intent 或正式 API 暴露故障注入能力。

ENOSPC 工具先建立独立 mount namespace 并设为 private，挂载 12 MiB tmpfs；由独立 app_process 初始化 Android 环境后降为应用 UID 10386，执行真实 IndexStore/RescanMerger。SELinux 上下文仍来自 Root 引导，这不是普通应用沙箱权限测试。填充仅发生于该受限文件系统，释放空间后 reopen/recover，并检查原记录和 PRAGMA integrity_check。不会填满设备 `/data`、替换正常挂载或写入用户库。该工具需要设备现有 Root 授权和 BusyBox mount。

## 性能口径

静态文件树先含 100 个目录、每目录 999 个空文件，再增加 900 个同样目录。小目标原有一个文件，重扫时增加一个非空标记文件；大目标原有 100,001 个节点，重扫时增加标记文件。测量范围是同一快照更新，不新增快照。每轮同时保留较小的历史快照，数据库总行数大于单个目标快照。

使用关闭 SCAN_BENCHMARK 的优化 Debug 隔离宿主，默认 4096 动作导入。每次真实遍历、pipe、暂存及合并；不是合成 SQL 行回放。资源在独立客户端线程中采样，查询走 Binder 到普通 UID 扫描服务；目标间没有清系统文件缓存。名义采样间隔为 50 ms，加上查询和采样本身耗时，因此这些是采样最大值，不是精确峰值。

这里的优化 Debug 指默认导入、pipeline 和最终处理策略；native 保留 Debug 编译参数。日常交付包另外审计为 native -O2。下面的数据不是最终 Profile 的性能重复实验，也不是 Root 后端性能对照。

第一轮调整后结果：

| 历史快照/本次新子树节点 | 端到端秒 | 扫描+暂存 ms | 应用至提交标记 ms | COMMIT ms | WAL 采样 MiB | 服务 RSS 采样 MiB | 目录查询最大 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 100,004 / 3 | 0.634 | 2 | 581 | 7.2 | 0.50 | 142.1 | 2.1 |
| 100,005 / 100,002 | 7.028 | 3008 | 3654 | 267.9 | 100.96 | 145.9 | 270.0 |
| 1,000,005 / 3 | 6.857 | 4 | 6785 | 11.2 | 0.50 | 140.0 | 22.7 |
| 1,000,006 / 100,002 | 14.674 | 2956 | 11073 | 544.3 | 102.82 | 142.2 | 530.7 |

第一轮记录：`.local/snapshot-rescan-step6-acceptance-20261119-03/performance.json`。原失败批次 `-01/-02` 不作为通过证据。`-01` 使用了超出接口上限的测试分页参数；`-02` 的内容断言没有排除任务发现字段，且发现了百万历史索引的大子树超时；均已修正或解决。

任务 metrics 分别保留 import、validation、replacement、initialization、owner 收集/应用、aggregation。producer elapsed 包含 pipe 背压，import 与 producer 并行，不叠加为总耗时。应用至标记不含最终 SQLite COMMIT；COMMIT 由同进程单调时钟的 BEFORE_COMMIT/AFTER_COMMIT 测量。端到端包含启动、提交、状态查询和采样退出，终态持久记录与 journal 发布不承诺跨文件原子性。

首次百万小目标：校验 454 ms，替换/成员 585 ms，基线初始化 1661 ms，owner 收集 2876 ms，owner 应用不足 1 ms，目录汇总 1168 ms。由此可见小目录仍有全快照 SQL 成本。大目标提交附近出现约 0.5 秒的查询延迟，不能宣称全过程查询始终低于一帧或 100 ms。

第二轮完整复测：

| 场景 | 端到端秒 | 扫描+暂存 ms | 应用至标记 ms | COMMIT ms | 查询 p50 / p95 / 最大 ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| 10 万历史，小目标 | 0.623 | 2 | 591 | 8.2 | 1.8 / 4.2 / 5.6 |
| 10 万历史，大目标 | 8.528 | 3021 | 3601 | 404.8 | 9.9 / 11.5 / 3121.1 |
| 百万历史，小目标 | 6.705 | 5 | 6594 | 18.0 | 1.8 / 11.8 / 25.9 |
| 百万历史，大目标 | 16.629 | 2866 | 10642 | 293.4 | 9.8 / 10.5 / 3014.8 |

两轮范围分别为 0.623–0.634、7.028–8.528、6.705–6.857、14.674–16.629 秒；没有预热/随机轮换，不能将两次范围当成统计稳定性结论。第二轮总库采样最大约 1123.7 MiB，包含多个历史快照；WAL 最大约 102.6 MiB；服务 RSS 135.6–144.4 MiB，FD 最大 133。大目标暂存 100,002 行，终态全部清理。可读取服务 fd 链接，未在采样中观察到 etilqs 临时文件长度；这不等于临时 B-tree 不使用内存，也不能排除短暂文件峰值。

第二轮大目标在提交/暂存清理附近各出现一次约 3 秒的目录查询；10 万组其中一次返回 stale-cursor，符合跨 revision 查询语义。多数查询在 12 ms 左右以内，但最终事务和清理仍可能造成秒级尾延迟。这是当前版本的已知性能边界，后续可继续优化大子树提交后的清理/检查点；本轮不以隐藏慢样点或增加超时掩盖结果。服务端冲突写使用 busy 返回，不让 Binder 等待快照 mutation 锁；合并中真实读取/取消和无死锁已通过检查点测试。

第二轮记录：`.local/snapshot-rescan-step6-acceptance-20261119-04/performance.json`。端到端计时包含采样线程退出，个别慢查询会延长这个测量外壳的结束时间，不能把该值直接当作纯服务提交时间；任务分阶段指标和 COMMIT 分开列出。

## 验证与交付记录

| 项目 | 最终结果/证据 |
| --- | --- |
| Flutter analyze / 全量测试 | 无问题；92 个实际用例通过（machine 另含加载、setUpAll/tearDownAll 事件） |
| JVM | 13 项通过 |
| 核心、Root 服务、全量生命周期、外部打开 | 92 项报告：86 通过、6 预期跳过；跳过项为未启用的 benchmark、历史 Root/规模入口，Root 局部服务另有实际通过证据 |
| 真实强杀、合并取消及规模 | 两轮最终各 3 项全部通过 |
| 真实 ENOSPC | 12 MiB tmpfs，普通 UID 10386；旧 signature 7:3:3 保留，未提交，reopen/recover、integrity_check 通过 |
| native | 双 ABI 构建，arm64 实机 35 项通过 |
| 隔离 Flutter 真机 UI | 1 项通过 |
| 普通包 | Debug/Profile 双 ABI 构建；SCAN_BENCHMARK=false、RESCAN_TEST_HOST=false；Profile native -O2 的 build ID 审计通过 |
| Lint / diff | 最终 lint 0 错误、8 警告；diff --check 通过 |

主要证据：`.local/snapshot-rescan-step6-core-20261119-04/`、`snapshot-rescan-step6-acceptance-20261119-03/` 和 `-04/`、`snapshot-rescan-step6-enospc-20261119-06/`、`snapshot-rescan-step6-native-20261119-01/`、`snapshot-rescan-step6-ui-20261119-01/`，以及 `snapshot-rescan-step6-flutter.jsonl`、`snapshot-rescan-step6-lint-final.log`。

最终设备实际 APK SHA-256：`dd40720bd6e80a99a18314e2bc225d4fe9c35ebad4088880879665ce5a8d85f0`。arm64 build ID `25b178a7582c7784dd160cd0d8254777b46c0ddf`，x86_64 为 `b8cbc94fea346f175c36ce34f65c730ebec8203d`。普通优化包留在设备上，测试结束后没有恢复旧 APK。

日常库从 schema 4 增量升级到 5。升级前和最终清理后的库/WAL 副本在主机逐表比较所有原有列，包括原始 BLOB；12 个 session、8 份 snapshot、430,186 个 node/aggregate、213,661 个 hardlink 均完全一致，integrity_check 与 foreign_key_check 通过。原包和正常库/WAL/journal 备份均保留在 `.local/snapshot-rescan-step6-delivery-20261119-01/`。不将 schema 4 旧包直接覆盖到已经升级的日常库。

日常 UI 验收通过 ADB 点击实际默认入口的 Flutter 页面完成，不使用测试 Activity、Pigeon 测试替身或隔离宿主：先建立自有小树，删除 old.txt、创建 15 字节 new.txt，从目录菜单查看范围确认并提交。快照 ID 不变，revision 7→8，逻辑量 10→22 B，旧文件消失、新文件可见，原始扫描时间不变，快照总数 10→10（其中两份为本次自有全量样本）。最终 -O2 包再次把标记内容改成 18 字节，从快照根目录重扫，revision 8→9、逻辑量 25 B、成功次数 2；确认实际安装产物可用。

最后通过日常快照列表分别核验路径并删除两份自有样本快照，删除自有样本目录，恢复原最近扫描 journal。再次逐表核对，原有用户快照与节点保持一致。实际 `/data/data` 历史快照未被本轮局部更新；用户可按需自行选择真实目录试用。截图、确认框 XML、正常库检查及安装指纹在 delivery 目录中。

## 复现

```powershell
. ./.local/android-env.ps1
./tools/snapshot-rescan/verify-step6.ps1 -Serial '<device-id>' -RootInstall -RootTests
```

脚本依次执行静态检查、Flutter、核心/生命周期/外部打开回归、强杀和规模验收、真实 ENOSPC、native 边界测试及专用 Flutter UI 集成；最后构建关闭两个开关的普通 Debug/Profile，并串行运行 lint。每次隔离设备测试都会核对正常数据指纹、恢复原主包/测试包；脚本本身不会部署日常包，防止把临时验证误认为交付。完成包验证后，使用已有覆盖安装工具交付普通 Profile，并保留升级前 APK 和正常库/WAL/journal 备份。

单独重跑强杀和规模测试使用 `verify-step2.ps1 -IncludeService -AcceptanceOnly`；`-AcceptanceMethods` 可指定其中的方法。ENOSPC 使用 `verify-enospc.ps1`，须配合刚构建的隔离宿主主 APK/测试 APK；BusyBox 默认位于 `/data/adb/ksu/bin/busybox`，其他安装位置使用 `-Busybox` 指定。
