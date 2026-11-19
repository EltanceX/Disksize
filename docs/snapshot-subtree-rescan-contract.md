# 快照局部重扫：步骤 1 契约与回归基线

日期：2026-11-18。状态：步骤 1 已完成；局部重扫功能尚未接入应用。

本文冻结[实施方案](./snapshot-subtree-rescan-plan.md)的首版规则，定义后续 schema、合并和服务工作的验收边界。当前仅新增纯逻辑契约、测试基线和验证入口，不改变 schema 4、扫描入口或历史快照数据。

## 1. 首版契约

| 项目 | 冻结规则 |
| --- | --- |
| 操作 | 用户显式选中目录，递归重扫整个子树；不支持仅当前层、批量目录、自动刷新 |
| 身份 | 保留 snapshot ID、session ID 和所选目录 ID；成功后内容 revision 增加一次 |
| 输入 | `snapshotId` 为规范 UUID 格式，`nodeId > 0`，`0 <= expectedRevision < Long.MAX_VALUE`；服务端解析原始路径，不接受任意路径替代索引目标，revision 必须留有一次递增空间 |
| 快照可用性 | 目标必须是已最终聚合且 revision 匹配的可用快照；全局 Partial 不自动禁止局部修复 |
| 保留保护 | 已保留快照禁止更新；取消保留后才可重新提交请求。这是新增规则，不是当前界面已支持的行为 |
| 范围 | 使用原用户、后端、卷身份、挂载视图和统计规则，不跟随符号链接、不跨挂载 |
| 单任务 | 全量扫描和局部重扫共享一个执行名额；对目标的删除、保留变更等冲突操作返回 busy |
| 成功条件 | 新子树 producer 状态为 completed、子树完整且扫描问题数为零；Partial、资源跳过、权限/I/O 错误均不合并 |
| 目标消失 | 单次读取失败不视为删除；保留旧数据，提示重扫父目录 |
| 类型变化 | 所选目录本身变为文件或符号链接时拒绝本次更新；其后代类型变化可通过完整父子树替换体现 |
| 同路径重建 | 同路径新目录可替换旧目录内容，但仍需实时路径、身份及挂载安全核验，不要求 inode 永远等于历史值 |
| 发布 | 暂存、校验后，在一个最终事务中替换、重建统计、更新摘要和 revision，并记录提交结果 |
| 失败 | 提交前失败、取消或版本冲突，目标快照内容、摘要和 revision 不变；临时任务数据可以变化并单独清理 |
| 历史 | 保留原始扫描起止和创建时间，新增局部更新时间及范围；不自动保存完整旧版本，不提供提交后撤销 |
| 可见性 | 原始树外记录不重读、不伪造新时间；硬链接派生贡献及相关祖先统计允许变化 |

任务 completed 表示更新事务成功，不保证快照 complete=true。其他未修复范围仍可为未知；混合时间的硬链接元数据也可能产生冲突，最终快照应如实保持 Partial。

## 2. 可执行契约与状态机

实现位置：[SnapshotRescanContract.kt](../android/app/src/main/kotlin/com/example/disksize/scan/SnapshotRescanContract.kt)。该定义尚未由 Coordinator 调用，不表示权限检查、任务持久化或扫描流程已经实现。

```text
preparing -> scanning -> validating -> applying -> completed
任一非终态 -> failed / cancelled / interrupted / stale
```

- 不允许跳过成功路径中的阶段；所有终态都不再转入其他状态。
- `admissionError` 先验证请求格式，再按快照不存在、执行名额冲突、快照不可用、已保留、revision 冲突、索引节点不存在、非目录、身份不兼容的顺序确定错误。
- 开始前和应用更新前都需重新执行当前目标校验；当前任务占有名额不算与自身冲突。
- applying 阶段仍可在事务提交前回滚。状态转换合法，不等于可以跳过实际事务操作。
- 只有与新 revision 在同一事务中持久化的提交记录，才能确认完成；`committedRevision` 必须为正数。

### 2.1 取消与恢复

| 条件 | 取消响应 | 恢复结果 |
| --- | --- | --- |
| 非终态，尚无提交记录 | `cancellation-requested`；请求取消，不立即宣称已回滚 | `interrupted`，旧快照不变 |
| failed/cancelled/interrupted/stale，无提交记录 | `already-terminal` | 保持对应终态 |
| 已存在有效 committed revision，不论内存或旧 journal 状态 | `already-completed`，不能撤销 | `completed`，补发状态，不重复合并 |
| 状态声称 completed，但无持久提交记录 | 视为不一致，不返回成功 | 视为不一致，需调查/修复，不能制造成功记录 |

取消与提交的竞态必须由执行线程和数据库提交记录裁决；读取状态时的纯逻辑判断不能替代同步。事务回滚、进程死亡和 journal 补发的集成验证属于后续步骤。

### 2.2 错误码

| 错误码 | 含义及后续行为 |
| --- | --- |
| `invalid-request` | UUID、node ID 或 revision 格式非法，不创建有效扫描任务 |
| `snapshot-not-found` | 快照不存在，刷新列表 |
| `node-not-found` | 快照存在，但所选 node ID 不存在，刷新目录并重新选择；与磁盘目标消失不同 |
| `snapshot-unavailable` | 原索引尚未最终聚合或锚定版本失效 |
| `retained` | 已保留，需用户取消保留后重试 |
| `busy` | 另一个扫描/重扫或冲突操作正在占用执行名额 |
| `stale-revision` | 目标版本与 expected revision 不符；已创建的任务进入 stale，不自动覆盖或偷偷改用新版本 |
| `not-directory` | 索引选中项不是目录 |
| `incompatible-identity` | 用户、卷、后端、挂载视图或统计规则无法确认兼容 |
| `target-missing` | 当前目标无法确认仍存在，建议重扫父目录，不能据此清空旧子树 |
| `target-type-changed` | 所选目录当前不是目录，建议重扫父目录 |
| `root-unavailable` | Root 后端不可用，不降级合并，需恢复原后端 |
| `incomplete-scan` | 新子树 Partial、不完整或有扫描问题，整次拒绝合并 |
| `scan-failed` | producer 扫描失败 |
| `protocol-error` | 协议、计数或 producer 状态非法，拒绝使用数据 |
| `storage-error` | 暂存/写库失败或存储不足，提交前保留旧结果 |
| `apply-failed` | 合并、去重、聚合或事务应用失败，需整体回滚 |
| `cancelled` | 提交前取消已经实际完成 |
| `process-interrupted` | 执行进程或链路中断，尚未提交 |

错误码与人类可读说明分开，不把真实应用路径列表或文件内容写入通用日志。暂存或合并的具体异常到这些错误码的映射由后续服务实现补齐。

## 3. 规范化扫描结果

测试辅助：[SnapshotBaseline.kt](../android/app/src/androidTest/kotlin/com/example/disksize/index/SnapshotBaseline.kt)。仅用于受控小样本，可以持有完整测试结果；不得直接作为生产查询或百万节点导出实现。

规范化内容：

- 原始相对路径、名称、链接目标与 device/inode 按十六进制保存，路径以数据库 BLOB 字节顺序排序。
- parent 与硬链接 owner 转换为原始相对路径，移除 session/node ID 和本次分配顺序。
- 保留 kind、mode、UID/GID、mount、nlink、原始大小/占用、文件 mtime、valid、complete、enumerated 和 skip。
- 保留 own/total logical、own/total allocated、子树完整性，以及聚合的类型、深度和名称 key。
- 问题转换为路径、code、errno、operation，排序后保留重复项，不使用自增 issue ID。
- 保留快照状态、provisional、节点数、问题数、后端和最终容量/完整性。
- 排除 session 起止时间、采集/发布耗时、绝对临时根路径、volume 采样时间和具体 revision 值；文件本身 mtime 不排除。
- 捕获时仍检查 session/snapshot revision 一致、父节点与 owner 可解析、聚合引用一致，以及节点/聚合/问题计数匹配。

### 3.1 必须区分的两种对照

**真实静态树全量扫描对照**：固定同一棵树，分别完整扫描到独立测试快照，规范化结果应一致。修改树后再全扫，比较预置的结构、逻辑大小和 owner 期望。native 文件系统上的 inode、mtime 和 allocated 不写成跨设备固定常数。

**局部合并的混合时间对照**：以旧快照树外原始记录，加上新子树扫描记录，组成一份预期完整元数据流，使用全量导入/最终处理生成独立参考结果。后续局部合并实现必须与这份参考结果一致，而不是要求树外所有原始字段等于最新磁盘全扫。

例如删除 `a/shared` 后，树外 `outside/shared` 的实时 nlink 可能由 2 变为 1。局部刷新不访问树外路径，因此旧 nlink=2 必须保留，但容量 owner 可转移到 `outside/shared`。如果共享 inode 的大小/mtime 在范围内外出现矛盾，则按已有冲突规则保留未知，不能用树内新值覆盖树外原始记录。

步骤 1 只建立参考结果，不包含真正的子树替换。合并实现是否满足该对照，必须在步骤 3 另外验证。

## 4. 固定样本与期望

### 4.1 native 真实目录树

机器可读 golden：[snapshot-rescan-baseline-v1.json](../android/app/src/androidTest/assets/snapshot-rescan-baseline-v1.json)。所有数字为逻辑字节数，不是索引数据库文件大小。

| 指标 | 修改前 | 修改后 |
| --- | ---: | ---: |
| 节点数，含根目录 | 16 | 14 |
| 根逻辑大小 | 65 | 80 |
| `a` 子树逻辑大小 | 17 | 32 |
| `a-` 子树逻辑大小 | 11 | 11 |
| `a2` 子树逻辑大小 | 13 | 13 |
| `outside` 子树逻辑大小 | 24 | 24 |

受控修改：删除 `a/deleted` 5 B 和 `a/shared` 7 B；`a/change` 从 3 B 增到 9 B；新增 `a/new` 19 B；将 `a/replaced/old` 2 B 所在目录替换为同路径 4 B 文件。保留空目录和指向范围外文件的符号链接，确认不跟随链接。

每种状态各完整扫描两次，比较完整规范化结果；随后确认修改前快照仍保持原样。该 native 样本中的两个 shared 文件是独立普通文件，不冒充真实硬链接。

### 4.2 确定性元数据回放

| 指标 | 原始参考流 | 局部刷新后的混合时间参考流 |
| --- | ---: | ---: |
| 根逻辑大小 | 45 | 65 |
| 根报告占用 | 40,960 | 40,960 |
| `a` 子树逻辑大小 | 15 | 28 |
| 树外 shared 别名 own logical | 0 | 7 |
| 硬链接 owner | `a/shared` | `outside/shared` |
| 树外 shared 原始 nlink | 2 | 2，仍是历史记录 |

回放还覆盖非 UTF-8 加换行的原始名称 `ff0a`、改变节点遍历顺序和提交次数后规范化仍相等、文件 mtime 改变可被发现，以及树外不可访问记录仍使快照为 Partial。

## 5. 验证入口与结果

执行方式见[验证说明](../tools/snapshot-rescan/README.md)：

```powershell
. ./.local/android-env.ps1
./tools/snapshot-rescan/verify-step1.ps1 -Serial 7b9a982f -RootInstall
```

2026-11-18 在当前 Xiaomi sheng / Android 16 / arm64 设备上完成：

- 13 项 JVM 测试通过，其中 9 项为新增局部重扫契约测试，4 项为既有 Root 协议回归。
- 18 项真机测试通过，其中 4 项为新增基线测试，14 项为既有快照索引回归。
- schema 保持 4，没有调用局部合并、数据库迁移或新增扫描入口。
- 没有替换主 APK；设备仍运行普通优化版，SHA-256 为 `1ee4d9aeae2a8084a106cb2e6f0061e7d8ecfa3fa513734e254690a4518054d5`。
- 仅临时覆盖测试 APK，结束后恢复原测试 APK 并核对指纹；用户索引主库、WAL 和会话日志在测试前后指纹相同。
- 最终成功原始记录位于忽略目录 `.local/snapshot-rescan-step1-20261118-03/`，包含 build.log、instrumentation.log、summary.json 和原测试 APK 备份。

首轮 `.local/snapshot-rescan-step1-20261118-01/` 有 1 项测试在 `Os.link` 建树阶段收到 EACCES，未进入 native 扫描断言，其余 17 项通过。随后将 native 普通文件树与合成硬链接身份回放分离，不改变 SELinux、Root 授权或应用权限，复测全部通过。

剩余边界：本步骤未验证真实硬链接文件树的局部合并、Root 局部重扫、故障注入下的最终事务、UI、schema 5 或规模性能；这些仍属于步骤 2 至 6，不能由本次基线通过推断已完成。
