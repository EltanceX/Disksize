# 阶段 5：索引、聚合与分页验收

本页保留阶段 5 的协议 v2、schema 2 和性能基线。阶段 6 已非破坏升级至协议 v3/schema 3，并新增双向分页、范围查询和浏览界面；当前行为见[阶段 6 文档](./phase6-explorer.md)。

## 实现

普通扫描与 root 扫描共用 C++ 增量扫描器、v2 元数据协议和 Kotlin 接收器。
普通 UID 的 `:scanner` 服务负责 SQLite、会话 journal 和最终结果发布。
root 进程只读目标并写 pipe，不打开应用的数据库或缓存。
阶段 2 全树扫描器及 v1 协议仍供原型回归工具使用；应用扫描已切换到流式实现。

SQLite 位于应用 `no_backup/scan-index.db`，启用 WAL，schema 当前为 2。
版本 1 到 2 采用新增覆盖索引的迁移，保留已有节点；未知迁移明确失败，不删除数据库。
表包括 sessions、nodes、aggregates、issues、snapshots，以及聚合辅助表 hardlinks。
每个扫描拥有独立 UUID，历史会话不会被新扫描覆盖。
快照表现在记录最终索引的锚点、版本、时间、容量和完整性；命名、保留、删除及对比属于阶段 8。

单 worker 顺序写入，事务最多 256 条导入动作。接收器同步写库后才继续消费 pipe，形成端到端背压。
native 不持有全树，只有最多 512 层目录栈和约 32 KiB/256 条发送批次；单帧上限 64 KiB。
父路径缓存最多 32 条、合计 1 MiB；完整原始路径和 metadata 存在磁盘。
查询每页最多 100 条，详情仅加载一个节点；Dart adapter 不累积页面或整棵树。
SQLite page cache 每连接约 8 MiB、临时表使用磁盘，CursorWindow 和查询页有界。

名称、相对路径、符号链接目标使用原始 BLOB，device/inode 使用固定 8 字节 BLOB，避免无符号身份被 signed Long 损坏。
扫描不跟随目录项 symlink，不跨 mount；目录打开后核验 inode/device/mount，并在关闭时检测时间戳变化。
祖先目录 FD 保持打开，避免路径替换导致重定向；祖先身份循环也被拒绝。
目录深度、节点和系统 FD 限制均会留下不完整结果。

## 聚合与恢复

扫描期统计为 provisional，包含未去重的单项贡献。
最终归属按同一 device/inode 的最小原始相对路径字节顺序确定，与发现顺序无关。
alias 保留原始 metadata，计入容量为 0，详情提供 owner ID；目录自身 blocks 始终按有效且未跳过规则计入。
同 inode 的 size/blocks/mtime 不一致时，相关节点标为不完整并写入 changed-during-scan 问题。
随后从深到浅汇总，传播子项不完整状态；所有加总限定 signed 64 位，溢出使会话失败。

硬链接分组每次查询最多 1,024 组，最终更新按 4,096 个 ID、目录汇总按 1,024 个节点分批提交，并检查取消。
分组查询支持 SQLite CancellationSignal；单个 SQL 批次执行期间不能逐行中断。
进程死亡时保留已提交批次，恢复为 interrupted/provisional，不当作最终结果。
取消或导入/聚合失败不创建可用快照；旧会话不受影响。
terminal journal 持久化完成前，公共 API 不暴露索引终态。

## 查询接口

Pigeon/AIDL 提供 `queryIndex`，使用有 schemaVersion 的有界 JSON；Dart 入口为 `AndroidIndexClient`。
`IndexRequest` 支持 session、children、large-files、detail、issues。
children 支持 logical、allocated 降序和原始名称升序；large-files 支持两种容量降序。
容量排序以 node ID 降序作为并列键，名称排序以 ID 升序作为并列键；错误列表以 issue ID 升序。
采用 keyset 分页，不使用 OFFSET。

游标绑定会话、查询种类、父节点/节点过滤、排序、页大小、revision 和最后一个复合键。
每批提交和聚合更新都会推进 revision。
查询前后校验 revision，仅返回稳定读取区间；continuation 版本不同返回 `stale-cursor`，应清空游标重新查询。
其他状态包括 ok、not-found、invalid-query 和终态发布期间的 index-finalizing。
详情提供原始路径、symlink 目标、uid/gid/mode、device/inode、mount、nlink、原始容量、mtime 和 skip 原因。
错误分页只返回节点 ID，按需调用详情，避免大量长路径撑大 Binder 返回。

## 固定设备性能

2026-11-15，Xiaomi sheng / Pad 6S Pro，Android 16 API 36、arm64、4 KiB pages、F2FS。
Debug APK，普通 UID、真实 CoordinatorService / JNI / pipe / SQLite / Binder，设备处于 SELinux Enforcing。
这不是跨设备性能保证；数据与原始内存采样保存在本机 `.local/phase5/`。

| 节点数 | 扫描与入库 | 最终聚合 | 首页 / 末页 | RSS 高水位 | 采样 PSS 峰值 | 新增索引 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 10,001 | 0.768 s | 0.473 s | 19.65 / 5.46 ms | 183.77 MiB | 84.86 MiB | 9.14 MiB |
| 100,001 | 6.777 s | 5.156 s | 22.31 / 3.40 ms | 215.84 MiB | 105.83 MiB | 92.48 MiB |
| 1,000,001 | 59.026 s | 54.082 s | 14.82 / 3.50 ms | 144.14 MiB | 54.25 MiB | 1,011.41 MiB |

样本分别含 9,900 / 99,000 / 999,000 个真实零长度文件，剩余为目录。
零长度文件制造大量容量并列项；分别遍历 99 / 990 / 9,990 页，使用 BitSet 核对全部文件 ID，无遗漏、无重复。
百万节点的最慢单页为 27.27 ms。RSS 来自 `/proc` 的进程高水位，PSS 为约每秒采样的最大值，可能漏掉短暂峰值。
各规模在独立进程运行，ART、共享页及 GC 时机使 RSS/PSS 不随规模单调增长，不能用两组差值推导每节点内存。
本组浅树 native 峰值为 2 个目录 FD，最终为 0；深层 FD 上限另由原生回归覆盖。

扫描与数据库导入重叠执行，第一列包含背压，不能把它与写入活跃时间相加。
导入数据库操作累计活跃时间分别约 0.613 / 5.286 / 55.305 s；生成样本、分页遍历和清理不计入扫描/聚合耗时。
数据库大小为本轮新增主库文件量，WAL 约 512 KiB；保留的历史索引会继续占用磁盘。

Root 另实测 100,001 个节点：扫描与入库 6.797 s、聚合 5.022 s，990 页核验全部 99,000 个文件。
首页/末页 26.21 / 3.41 ms；普通扫描服务 RSS 高水位约 215.12 MiB、采样 PSS 峰值 103.75 MiB；
UID 0 producer RSS 高水位约 183.46 MiB、采样 PSS 峰值 65.18 MiB。
root producer 使用 app_process/ART 和 libsu，这些数值包含运行时开销；本次未做百万节点 root 后端性能测量。

规模验证中发现并修复了两个查询计划问题：

- 不完整子项检查新增 `(session,parent,complete)` 覆盖索引，避免每个节点扫描整个会话。
- 硬链接 GROUP BY 自身采用 keyset 分批，避免 Android CursorWindow 重填反复执行整次聚合。百万节点最终聚合从 134.8 s 降至 54.1 s。

## 验证与限制

设备测试覆盖实际元数据与独立 stat 核对、稀疏文件、确定性硬链接及 metadata 变化、错误列表、同容量分页、详情、旧游标、schema 迁移、协议畸形输入以及进程恢复。
目录打开后发生替换时，后续 skip 更新会撤回其先前计入的自身 blocks；这一边界有独立回归用例。
root 的阶段 0 样本仍为 169 节点、logical 1,073,746,077 bytes、allocated 660,480 bytes，与独立基线一致；数据库由普通应用 UID 持有。
完整复现入口见 [阶段 5 工具说明](../tools/phase5/README.md)。

已通过 Flutter analyze、10 项 Flutter 单元/组件测试、2 项普通模式集成测试（含实际 Pigeon 分页/详情/错误查询）及 2 项 root 集成测试。
root 集成覆盖原始字节名称、共享存储双入口、普通 UID 所有权、取消、真实 root/客户端死亡和显式重连；扫描前后的样本 metadata 与独立 stat 一致。
Android instrumentation 的 9 项正确性用例及 4 次实际规模测量均通过；规模入口单独运行，默认套件会跳过该入口。
另通过旧核心 8 组、流式核心 8 组、真实 pipe 3 组和 v1 JVM 协议 4 项回归；Pigeon 再生成输出一致，双 ABI Debug APK 构建及 Android Lint 均通过。
最终 Lint 为 0 errors、8 warnings；普通 dashboard APK 已安装回设备，测试样本已清理，所有文件访问 AppOp 为 default、dataSync 自定义 timeout 为 null。

最终 APK：`build/app/outputs/flutter-apk/app-debug.apk`，包含 arm64-v8a 和 x86_64。
SHA-256：`82169728799D7D61280F7D15880E32D7E91418B8E05B672A56DFD5DBDFB5CE06`。

当前最多 2,000,000 节点、512 层深度，文件系统扫描始终是时间区间观测，不是文件系统原子快照。
普通/root 的跨设备、API 29～35、其他 root 管理器及锁定用户矩阵留在阶段 9。
百万节点索引约 1 GiB，是当前完整路径存储和多排序索引的实际代价，后续可评估路径压缩及精简索引。
全目录浏览、面包屑、交互排序及虚拟列表属于阶段 6；本阶段完成底层 API 和有界 Dart 模型，首页保留有限预览。
