# 局部重扫步骤 4：扫描服务与桥接

日期：2026-11-18。

状态：步骤 4 已实现并完成普通与 Root 真机回归。schema 保持 5，元数据协议保持 v4，继续使用优化后的流式 producer、4096 动作暂存批次和普通 UID 数据库合并。本文记录步骤 4 时的基线；后续目录界面及隔离真机交互验证已在[步骤 5](./snapshot-subtree-rescan-ui.md)完成，日常包交付仍待步骤 6。

## 接口与状态

Pigeon 定义位于 [scanner_api.dart](../pigeons/scanner_api.dart)，Kotlin/Dart 生成文件已同步更新。新增三个异步接口：

```text
startSnapshotRescan(snapshotId, nodeId, expectedRevision) -> JSON
getSnapshotRescan(taskId) -> JSON
cancelSnapshotRescan(taskId) -> JSON
```

调用方只能传快照 ID、索引节点 ID 和 expected revision，不能传文件系统路径。控制及状态通过 Coordinator AIDL，原始元数据仍走 v4 pipe，不把树装入 Binder 或 JSON。

| 调用 | 返回约定 |
| --- | --- |
| start | 成功为 `status=ok`，包含 taskId 和 preparing 任务；语义拒绝使用步骤 1 的错误码作为 status；只有实际创建了持久任务才返回对应 taskId |
| get | `status=ok` 及有界任务记录；未找到为 `not-found`，非法 ID 为 `invalid-request` |
| cancel | `cancellation-requested`、`already-completed`、`already-terminal`；不存在或非法请求单独返回，不把提交后的任务报告为取消成功 |

任务返回 snapshot/session ID、目标 node ID、expected/committed revision、阶段、错误、计数、各阶段时间、取消请求及暂存清理状态。相对路径最多返回前 4096 原始字节的 hex，并用 pathTruncated 标记截断；执行始终使用完整路径，不将截断展示值作为扫描输入。

[snapshot_rescan_client.dart](../lib/platform/snapshot_rescan_client.dart) 提供独立 `SnapshotRescanClient`、任务模型及携带错误码的异常；未并入最近全量扫描的 `ScanClient.snapshot()`。传输/服务异常仍使用现有 FlutterError/PlatformException，语义拒绝不被折叠成笼统的连接失败。

## 前台服务与单任务调度

[ScannerPlatformApi.kt](../android/app/src/main/kotlin/com/example/disksize/bridge/ScannerPlatformApi.kt) 要求 Activity 处于前台，先调用 Coordinator 准备持久任务，再启动现有 dataSync 前台服务。启动被拒绝时调用 rejectPreparedScan 收尾；准备后 10 秒未收到启动也会失败并释放暂存/任务槽，不留下永远 busy 的 preparing 任务。

[CoordinatorService.kt](../android/app/src/main/kotlin/com/example/disksize/scan/CoordinatorService.kt) 的全量和局部任务共用一个活动槽及串行索引 worker：

1. Binder 校验调用 UID、请求格式和活动槽；仅将短的持久化准入/范围解析交给后台 worker，并设置有界等待，不在 Binder 上执行遍历或聚合。
2. 后台事务创建任务，复核快照、revision、保留保护及扫描身份；Root 必须已有显式授权且连接 ready，不自动调用 su 或降级普通模式。
3. 前台服务提升成功后，worker 再读一次受信任范围，经普通或 Root producer 写入隔离暂存。
4. producer 正常关闭后保存 validating 摘要；范围安全错误先映射为结构化错误，其余完整性校验和最终原子替换由步骤 3 的 apply 负责。
5. 完成、失败或取消后释放 producer/句柄，再补齐适用的 journal 并释放活动槽与前台通知。

局部任务的进度和状态不写成新的全量 ScanSnapshot，也不增加普通 session 或 snapshot。扫描中可查询旧索引，状态查询使用 WAL；通知使用局部任务计数，不污染首页最近全量扫描摘要。

本版局部任务期间，快照删除、保留变更及对比构建统一返回 busy，包括其他快照，以避免与暂存写事务争用，并避免删除最近会话留下旧 journal。快照列表在扫描阶段仍可读，在应用阶段返回 busy。普通目录/详情/大文件/树图查询继续沿用 WAL 和 revision 检查。

共享快照锁改为 ReentrantLock；服务的 snapshot 查询使用 tryLock，竞争时直接 busy，消除“先检查阶段，再碰上合并锁”的等待竞态。内部数据库测试/调用保留可等待的串行入口，合并与缓存计算仍先锁后事务。界面侧 busy 的专门反馈属于步骤 5。

## 身份与原始路径

[SnapshotRescanSource.kt](../android/app/src/main/kotlin/com/example/disksize/index/SnapshotRescanSource.kt) 从持久任务和正式索引读取范围：

- 复核 snapshot/session revision、可用性、保留状态、原始目标路径及父链结构。
- 要求普通/Root 后端已知，统计规则、Android 用户及 boot 身份与当前记录一致；已记录卷 UUID 时也需一致。
- 本版要求同一次启动。快照对比可依靠稳定卷 UUID 跨启动，但局部合并保留树外旧 device/inode，不能据此直接放宽到跨启动；不兼容时应新建完整快照。
- 按索引父 ID 收集从快照根到目标目录的 device/inode/mount，检查有效、未跳过、深度与原始路径一致；不通过显示文本或有损 UTF-8 重建。

新增 `NativeBridge.nativeCreateSubtreeScan(rootBytes, relativeBytes, identities, filesystem)`。普通与 Root 使用同一个 [ScopedRoot](../android/app/src/main/cpp/core/subtree_scope.cpp)：

1. 仅在实际执行后端的命名空间解析原快照根，沿 canonical 根逐级 no-follow 打开；Root 路径不由普通进程 canonicalize。
2. 从固定快照根逐组件 openat，拒绝 NUL、空组件、`.`、`..` 和非法长度，不使用拼接后的整条绝对路径绕过深路径限制。
3. 保持祖先 FD，验证祖先 inode、全部节点 device/mount 和 filesystem；目标本身允许同路径新目录替换 inode，但不允许变成文件或符号链接。
4. 扫描使用已打开的目标 FD，沿用不跟随链接、不开普通文件内容、不跨挂载及目录打开后的身份校验。
5. 结束时再次验证根路径和所有命名绑定；扫描过程中移动/替换祖先或目标不会把已脱离原路径的结果合并回去。

旧 Root 快照可能保存 `/data/data` 一类 alias。它们在原根 inode 未变时可继续解析；若选中整个快照根且 alias 后的根已经重建，本版保守拒绝，不凭未知的旧 canonical 路径放行。直接保存 canonical 路径的根及普通子目录允许同路径目录重建。不可确认的历史身份、挂载变化或祖先变化均拒绝，不覆盖旧快照。

全局深度限制按 `512 - 目标原深度` 传给 native，合并后总节点数仍由最终事务校验，不能利用子树根深度归零绕过限制。FD、路径组件和索引父链均受深度上限约束，遍历不在内存中保留完整树。

## Root 与取消

[RootBackend.kt](../android/app/src/main/kotlin/com/example/disksize/root/RootBackend.kt) 新增原始字节的 runSubtree，Root AIDL 只传有界命令、范围身份和 pipe。RootScannerService 对每个新方法继续校验普通应用调用 UID、Root UID/eUID、单任务及输入大小；Root 进程仍不打开数据库或 journal。

取消先设置内存标记并触发 CancellationSignal，再通知 native/Root producer、关闭消费 pipe；持久化取消意图排在 worker 上，不依赖另一个被最终写事务阻塞的 UPDATE 来停止 SQL。普通链路等待 producer 线程退出，Root 链路使用有界 awaitIdle 确认该任务 producer 关闭；生产者死亡、Binder 断开或协议失败不能被当作完整结果合并。

任务提交前的取消回滚，服务中断使用 process-interrupted；已有 durable marker 时始终 completed。Root 取消和死亡会清理暂存，原快照与 revision 保持不变，重新显式连接后可重试。存储故障若也阻止失败/取消状态写入，则阻止继续准入并交由服务恢复处理，不删除目标快照。

## Journal 与恢复

[SnapshotRescanStatus.kt](../android/app/src/main/kotlin/com/example/disksize/scan/SnapshotRescanStatus.kt) 在提交后及启动恢复时对齐 journal：

- 仅 journal 自身 session 就是已更新快照时，更新计数、容量、完整性、预览、内容 revision 和局部更新时间/次数。
- 保留原全量 started/ended、扫描后端/身份和全量指标；历史快照更新绝不替换另一个较新的全量会话。
- SQLite 提交标记是完成依据；journal 写入或通知失败不改写 completed，不再次应用子树。
- 重启后先恢复数据库任务，再依据 snapshot revision 对齐 journal；未提交的任务 interrupted，已提交的任务保持 completed 并补清暂存。

两个文件不宣称跨文件原子提交。短暂发布间隔或 journal 写入失败时，全量摘要可以落后于数据库；重扫状态以数据库为准，恢复负责补齐。没有调用普通扫描的 interrupt 去删除或 provisional 化被更新的历史快照。

## 验证与复现

```powershell
. ./.local/android-env.ps1
flutter analyze
flutter test
./tools/snapshot-rescan/verify-step4.ps1 -Serial '<device-id>' -RootInstall -RootTests
```

2026-11-18，Xiaomi sheng / Android 16 / arm64：

| 层次 | 实际结果 |
| --- | --- |
| Flutter analyze | 无问题 |
| Flutter 全量测试 | 69 项通过，含 4 项新增客户端/Pigeon channel 测试 |
| JVM | 13 项通过 |
| 设备完整回归 | 76 项中 75 项通过、1 项 benchmark 查询计划测试预期跳过，0 失败 |
| native | 35 项通过：新增范围安全 14 项、原 stream 10 项、scanner 8 项、pipe 3 项；arm64/x86_64 均构建，arm64 真机执行 |
| 关闭测试宿主的普通 Debug | 原 66 项中 65 项通过、1 项预期跳过 |
| 普通构建与 Android lint | Debug/Profile 双 ABI 构建成功，两个测试开关均为 false；lint 0 错误、9 项警告 |

新增 `SnapshotRescanServiceTest` 10 项覆盖：真实平台桥接/前台启动、最新会话 journal 与旧游标、历史会话不串写、任务互斥/启动拒绝/准备期取消、请求/stale/retained/类型拒绝、用户/boot/规则身份拒绝、目标消失/类型变化/祖先替换/目录重建、普通 producer 取消与 Coordinator 强杀、提交后 journal 故障恢复、Root 原始非 UTF-8 名称与显式重连、Root 取消/生产者强杀/重连。取消/死亡使用 30,000 文件的自有样本，强杀前核对 PID 所属进程及 UID。

native 范围测试另验证目标及祖先在扫描期间重新绑定、错误 device/mount/filesystem、非法相对组件、根目录重建和 FD 清理。原 stream/scanner 回归保留真实隔离命名空间中的同设备 bind mount 测试，不更改设备全局挂载或 SELinux。

完整最终记录：`.local/snapshot-rescan-step4-20261118-04/`；普通构建记录：`.local/snapshot-rescan-step4-normal-20261118-01/`；Flutter machine 输出：`.local/snapshot-rescan-flutter-tests-20261118.jsonl`。`-01` 的桥接用例未正确前台化 Activity，`-02` 遇到旧 AndroidX ActivityScenario 在 target 36 下的 receiver flags 限制；随后复用已有的显式前台测试 helper，`-03` 与 `-04` 完整通过，最终以 `-04` 为准。未修改 AndroidX 依赖或降低 target SDK 来绕过测试。

普通 Debug/Profile 构建日志为 `.local/snapshot-rescan-step4-production-build-20261118.log`。该命令的两种 APK 构建成功，直接附带运行 lint 时遇到 Flutter 生成的 ignored `local.properties` 的 Windows drive colon 转义错误；随后使用仓库已有 `tools/lint-android.ps1` 规范化本机配置并重验通过，记录为 `.local/snapshot-rescan-step4-lint-20261118.log`。9 项警告涉及存储权限/目标 SDK、依赖版本、既有路径/资源及 KTX 风格建议；没有为通过检查而抑制错误、修改依赖或改动用户配置路径。

服务测试构建使用仅 debug 可启用的 `RESCAN_TEST_HOST=true`，将 Coordinator 的库/journal 固定隔离到 DE 的 `snapshot-rescan-service-test-v1.*`；默认、Profile、Release 均为 false，不开放任意存储路径的 Intent 入口。测试仍执行实际普通/Root 服务、native、v4 和合并代码，不启用 benchmark 策略。正常构建另行回归。

脚本临时覆盖安装主包与测试包，备份并在 finally 恢复原 APK；正常 CE 主库、WAL 和 journal 的 SHA-256 前后一致，测试 DE 库在停止应用后按固定名称清理。没有卸载主应用、清空用户数据、扫描真实 `/data/data` 或修改 Root 授权策略。恢复的原优化 Profile APK SHA-256：

```text
1ee4d9aeae2a8084a106cb2e6f0061e7d8ecfa3fa513734e254690a4518054d5
```

## 下一步

步骤 5 的目录/树图/详情入口、确认、任务进度/取消、busy/错误反馈、定位/游标重置及混合时间说明现已完成，详见[步骤 5 记录](./snapshot-subtree-rescan-ui.md)。

尚未交付包含新功能的日常包，未对用户快照执行重扫。大型历史快照的全局 SQL 重算、锁等待、WAL/空间峰值、界面延迟及完整前台超时/权限撤销矩阵仍需步骤 6 实测；本步受控样本的通过不能代替性能与交付验收。
