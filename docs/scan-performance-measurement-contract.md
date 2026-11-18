# 扫描性能测量契约 v1

本契约定义普通与 Root 扫描在完整服务链路上的计时边界，对应[性能优化计划](./scan-performance-optimization-plan.md)步骤 1。复用本契约的 A/B/C 与回放入口见[基准工具说明](../tools/scan-performance/README.md)。

## 读取结果

- `IScanCoordinator.getScanSnapshot()` 的终态 JSON：顶层 `performance` 是测试采集的首选。它包含扫描、导入、聚合和发布；必须按 `sessionId` 匹配刚启动的扫描，且 `active=false`。
- `queryIndex({"kind":"session","sessionId":"…"})`：读取该会话已写入 SQLite 的 `metrics.performance`，适用于历史查询。表结构仍为 schema 4。旧会话没有 `performance`，原有指标继续可读。
- 恢复自终态 journal 的最新会话保留写入 journal 前可测的字段，包括 `finalize.commitMicros` 与 `finalize.previewMicros`。`publication` 只出现在现场读取的终态中：这些值需要在 journal 写完后才能得到，采集不会再写一次 journal 或数据库。进程重启后、下一次扫描替换当前结果后，不能补回这一组字段。
- 现有[阶段 5 规模工具](../tools/phase5/README.md)的报告增加顶层 `performance`，采集实时终态；原来的 `metrics` 仍来自 SQLite。此工具运行的是 Debug 完整链路。

`performance.version` 当前为整数 `1`，独立于快照 JSON 的 `schemaVersion`、SQLite schema 和 pipe 协议版本。单位在字段名中：`Micros` 为微秒、`Bytes` 为字节，其余为计数。持续时间在各测量方本地用单调时钟计算，累加后向下取整为微秒；两边只交换持续时间，不比较绝对时钟读数。所有时间都是经过时间，线程被抢占的时间也可能计入。`0` 是实测为零或不足一微秒；未完成或未采集的阶段省略对应字段。读端应按版本解释，允许同版本新增字段。

状态、完整性、节点和问题数仍以会话本身为准。收到有效的完整协议流只代表流已校验，扫描仍可能是取消、失败或 Partial。发生协议错误、进程死亡或中断时，只使用实际采集成功的阶段，不能把字段缺失解释为该阶段没有成本。

## 字段与计时区间

### `coordinator`：准备与流水线

| 字段 | 开始 → 结束 |
| --- | --- |
| `prepareMicros` | Binder 通过调用方 UID 校验 → 建立活动任务；含等待服务初始化、参数/授权状态检查及普通模式路径解析 |
| `foregroundMicros` | 活动任务建立 → 前台服务完成提升且任务被标记为运行 |
| `workerQueueMicros` | 任务标记为运行 → 扫描 worker 开始执行；含提交至 worker 前的短暂工作 |
| `beforeIndexMicros` | worker 开始 → 调用 `IndexStore.begin`；含等待活动 journal 写入及准备扫描上下文 |
| `indexSetupMicros` | 开始创建索引会话 → importer 构造完成并进入后端调用；可能含首次打开数据库或迁移 |
| `pipelineMicros` | importer 就绪 → 后端方法返回、importer 关闭；含普通 native 句柄创建、pipe 生命周期及 Root 扫描派发（不含事先的 Root 授权） |

### `producer`：native 发送侧

| 字段 | 定义 |
| --- | --- |
| `elapsedMicros` | 创建增量发送器起，到 summary 帧写完为止；包括遍历、编码、同步传输和背压 |
| `pipeWriteMicros` | 每帧 header 和 payload 写入函数的经过时间之和，含系统调用、短写重试及等待可写，不含上层 payload/帧头构建 |
| `pipePollMicros` | 写入返回 `EAGAIN`/`EWOULDBLOCK` 后，等待 pipe 可写的 `poll` 调用经过时间之和；是 `pipeWriteMicros` 的子项 |
| `frames`、`wireBytes` | 从 identity 到 summary（含）的帧数和线上字节数；每帧含 36 B header；不含测量帧和 end 帧 |
| `nodeBatches`、`maxNodesPerBatch` | 读端核对的节点批次数及最大批量，平均批量可用节点数除以批次数 |
| `nodesByKind` | 读端解码的各类节点数：`unknown`、`directory`、`regular`、`symlink`、`special`；含根节点，计数不受硬链接去重影响 |

普通与 Root 共用同一 native 发送器和严格接收器。增量协议升至 **v4**：summary（类型 5）之后必须有一帧类型 9，接着是 end（类型 6）和 EOF。类型 9 固定 40 B，依次为 5 个大端 `u64`：`elapsedMicros`、`pipeWriteMicros`、`pipePollMicros`、`frames`、`wireBytes`。接收端校验帧数、字节数和时间的包含关系，拒绝版本错配、缺帧、重复帧及尾随数据。Root 建连检查同步升级。原型 v1、Pigeon/Binder 接口及扫描统计规则不变。

### `consumer`：读帧与导入

| 字段 | 定义 |
| --- | --- |
| `elapsedMicros` | 开始解析流 → 验证 end 和 EOF；不含关闭 importer |
| `receiveMicros` | 读取帧头、验证版本/会话/序号、读取 payload 并确认最终 EOF 的累计时间；含阻塞、复制与帧头处理 |
| `decodeMicros` | 非 end 帧在读完 payload 后的处理时间，扣除已计入的导入和进度回调。包含解码、验证、对象构造和计数，是批次计时的近似值 |
| `importMicros` | 累计导入操作时间，含构造路径/查父节点、事务开启及提交、卷容量记录 |
| `importCommitMicros` | 有活动事务时的 flush，包括更新会话进度/revision 及提交；已包含在 `importMicros` 中 |
| `progressMicros` | 同步进度回调的累计时间；不包含投递到主线程后的通知工作 |
| `transactions`、`committedActions`、`maxActionsPerTransaction` | 已成功提交的事务数、事务内导入动作总数、单事务最大动作数；动作含节点、目录关闭、问题与卷记录，不等于节点数 |
| `limitCommits`、`progressCommits`、`summaryCommits` | 按达到上限、进度帧、summary 帧区分实际提交次数；空 flush 不计 |
| `intervalCommits`、`deferredProgressFlushes` | 活动时间限制触发的提交，以及有打开事务但合并收集的进度帧次数；后者不计入 `transactions` |
| `maxActiveTransactionMicros` | 成功提交事务中，从第一项导入动作开始到调用 flush 的最长经过时间；不含提交本身，不保证操作或 pipe 阻塞时也能中途提交 |
| `importVariant` | 导入策略名称；历史报告中可能缺失。协议节点批量与事务动作上限独立 |
| `parentCacheHits`、`parentCacheMisses` | 非根节点的父路径缓存命中及查库次数 |

`receiveMicros + decodeMicros + importMicros + progressMicros` 近似覆盖 consumer 的内部工作，不能再加 `importCommitMicros`。两侧并行运行，`producer.elapsedMicros` 和 `consumer.elapsedMicros` 都落在 `coordinator.pipelineMicros` 内，不能相加。减去 `pipeWriteMicros` 得到的也不是纯遍历，因为还包含编码和其他 native 开销；纯遍历由步骤 2 的模式 A 单独测量。

### `finalize`：最终聚合与索引提交

| 字段 | 开始 → 结束 |
| --- | --- |
| `dedupMicros` | 进入 finalization → 更新会话阶段为 aggregating；含准备与硬链接身份分组 |
| `initializeMicros` | 身份分组后 → 写完 owner、别名容量及元数据冲突更新 |
| `aggregateMicros` | 初始化后 → 按深度汇总容量和完整性，判定最终扫描状态 |
| `commitMicros` | 开启终态数据库事务前 → 事务返回；含会话、快照及 revision 写入 |
| `previewMicros` | 终态事务返回 → 生成用于 journal 的有界结果预览 |
| `variant` | 最终处理策略；当前默认为 `optimized`。历史记录可能缺失，基准构建可对照其它策略 |

前三项首尾相接，已存入 SQLite；后两项测得时事务已完成，因此只进入终态快照及 journal。创建快照与提交位于同一事务内，不能把 `commitMicros` 当作纯 fsync 时间。

### `publication`：终态日志与服务发布

| 字段 | 开始 → 结束 |
| --- | --- |
| `journalQueueMicros` | 终态序列化结束并准备入队 → journal worker 开始写入 |
| `journalWriteMicros` | 调用 `SessionJournal.write` → 返回；包含编码与 `AtomicFile` 写入和提交 |
| `prepareToPublishMicros` | 通过 `prepareScan` 调用方校验 → journal 写完、即将把终态发布给 Binder 调用者 |

`prepareToPublishMicros` 包含上述流水线、聚合及 journal 等各阶段和中间间隔。它不包含打开 app、之前的权限/Root 请求、UI 侧连接建立、Binder 拉取后的处理及界面绘制。完整的用户请求到可浏览界面时间、单独的 Root 授权/连接时间、查询和取消指标由后续测试入口采集，不以服务发布时间冒充。

## 与历史指标的关系

`scanMicros`、`importDbMicros`、`finalizeMillis`、`dedupMillis`、`initializeMillis` 保留历史边界与单位。`scanMicros` 是核心带回调的区间，可能包括传输阻塞；部分尾帧在该区间之后发送。`importDbMicros` 是原有导入累计计时，含提交但不含卷记录。`finalizeMillis` 包含两个原毫秒子项，但截至终态数据库事务之前。`startedAtMillis` 与 `endedAtMillis` 属于墙上时间，继续用于会话展示，不用于替代单调时钟耗时。

默认只累加帧、事务与阶段计时，进度计时沿用现有的限频回调；沿用已有逐项导入时钟，不新增逐项计时或日志。进程 CPU、内存、FD、独立 SQLite 主库和 WAL 文件大小由基准工具单独采样；文件长度不等于设备写入量，采样峰值也可能漏掉瞬时峰值。编码独立耗时、GC 和设备 I/O 不属于本契约的内嵌指标。正式比较需要同构建、同样本和重复测量，本契约不提供加速数字。

## 验证

设备回归通过普通和 Root 的独立小样本检查计数、计时包含关系及终态发布。普通模式另验证 SQLite 留存与进程重启后恢复的字段差异。Root 用例 `IndexDeviceTest#rootScanUsesSamePerformanceContract` 仅在 instrumentation 传入 `-e scanPerformanceRoot true` 时运行，使用应用自有样本，需开发包已有 Root 授权。另运行 `realFileScaleBaseline` 的 10,001 节点普通模式确认规模报告也能导出实时终态；单次 Debug 冒烟结果不作为性能基线。
