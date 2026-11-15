# 阶段 3：普通模式与 Android 生命周期

日期：2026-11-15

状态：阶段 3 已完成；普通模式、真机前后台、进程死亡、权限撤销与系统超时验收通过。

## 1. 实现范围

首页已接入普通权限扫描，可选择应用自身目录、应用外部目录、主共享存储或自定义绝对路径。界面显示权限、进度、取消、完成/部分完成/失败/中断状态、已知容量、问题范围和有限结果预览；右上角保留运行环境检查入口。

扫描仍只读取元数据，实际执行 UID 是普通应用 UID；没有通过 shell、`su` 或 UI 进程提权。应用内 root 后端在阶段 4 接入，完整索引与分页在阶段 5，完整目录浏览在阶段 6。

## 2. 进程、线程与命令

```text
Flutter ScanDashboardPage / AndroidScanClient
  → Pigeon ScannerHostApi
  → ScannerPlatformApi / CoordinatorConnection
  → AIDL IScanCoordinator
  → CoordinatorService (:scanner，非导出、同应用 UID)
  → 单个扫描工作线程
  → NativeBridge / JNI 会话句柄
  → disksize_core（默认两个遍历线程）
```

开始命令分为准备与启动：Binder `prepareScan` 校验参数并预留唯一会话，网关再次确认 Activity 位于前台，再用显式 Intent 启动前台服务。`onStartCommand` 先提升前台状态，再投递扫描工作线程。未收到启动的准备会话在 10 秒后失败；系统拒绝启动时主动结束准备记录。每次只允许一个活动会话，包括原生清理和终态落盘期间。

Binder/Pigeon 只负责投递、取消、权限和有界快照查询，目录遍历不在 UI、Android 主线程或 Binder 调用内执行。连接断开后释放旧绑定，下次查询重新绑定；Activity/Flutter 引擎销毁仅解绑，不取消已经启动的前台任务。

JNI 句柄由受互斥保护的注册表管理，运行侧持有 shared_ptr；创建、执行、外部取消、释放分开。进度回调从原生线程 attach 到 JVM，调用后 detach，扫描结束后释放全局引用。根路径使用标准 UTF-8 字节传入，文件名原始字节使用十六进制传输，避免 JNI modified UTF-8 破坏异常名称。

服务只持有一个工作任务，原生队列继续有界。本阶段 Android 入口限制 100,000 节点、默认深度 512、保留 64 个问题；达到上限返回 Partial/ResourceLimit。独立 CLI 默认节点上限仍为阶段 2 的 1,000,000。

## 3. 普通模式权限

| 系统/入口 | 实现行为 |
| --- | --- |
| 应用自身 files 目录 | 不申请存储权限 |
| 应用 external files 目录 | 不申请所有文件访问，路径不可用时不展示入口 |
| API 29 共享真实路径 | Manifest requestLegacyExternalStorage + 运行时 READ_EXTERNAL_STORAGE，实际读取 Environment.isExternalStorageLegacy 判定可用性 |
| API 30+ 共享真实路径 | Environment.isExternalStorageManager，进入本应用“所有文件访问”设置，找不到专用设置时退回总设置页 |
| 自定义真实路径 | 以当前普通权限尝试，保留不可访问范围；不能提供 content URI 或 root 能力 |
| API 33+ 通知 | 独立请求 POST_NOTIFICATIONS；拒绝后仍可启动合法前台扫描 |

Manifest 中 READ_EXTERNAL_STORAGE 的 maxSdkVersion 为 29。所有文件访问不等于可访问其他应用私有数据，`Android/data` 等范围仍以实际系统行为为准。权限状态在页面恢复前台时重读。

共享扫描启动时要求对应共享访问权限；自身目录不受此条件影响。针对依赖主共享存储授权的活动扫描，每 500 ms 检查授权，并在结束前再次检查；撤销后请求取消并记 Interrupted/permission-revoked。如果系统直接终止进程，则通过持久记录恢复为 Interrupted/process-interrupted。自定义路径产生的普通 DAC/SELinux 拒绝仍由核心按节点记录。

## 4. 前台服务与生命周期

采用 `dataSync` 前台服务类型，适用于本地文件处理，声明 FOREGROUND_SERVICE 和 FOREGROUND_SERVICE_DATA_SYNC。扫描由应用前台的用户操作启动，不声明启动广播或自动后台扫描。

服务创建低重要性通知渠道，通知点击返回应用，并提供当前会话的取消 PendingIntent；PendingIntent 均为 immutable。主界面与通知调用相同的取消逻辑。原生进度约 150 ms 合并，页面约 400 ms 查询，通知更新至多约每秒一次。通知授权与存储授权互不替代。

应用进入后台或页面销毁时停止页面轮询；任务继续由前台服务持有，页面恢复后查询同一会话。切换到运行环境页面不改变任务所有权。服务使用 START_NOT_STICKY，不自动重启或续扫被中断任务。

Android 15+ 的 dataSync 后台时限由系统统一计算，通常为每 24 小时 6 小时，同类型前台服务共享配额。实现 `Service.onTimeout(startId, fgsType)`：设置 foreground-timeout 原因、请求原生取消，立即撤去前台状态并 stopSelf，不等待阻塞 I/O 完成才响应系统时限。服务被销毁时保留中断状态；正常绑定仍存在时原生线程完成清理并返回相同中断原因。

取消检查还覆盖了一个外部线程竞争条件：仅修改 CancellationToken 不会唤醒 C++ 条件变量，而工作线程可能在取任务前就因取消退出。协调器现在至多每 25 ms 复查取消，避免停留在 Cancelling；已补 40 次异步取消原生回归。阻塞内核 I/O 仍可能延迟原生收尾，界面保持“正在停止”。

## 5. 会话持久化与数据边界

`SessionJournal` 在 `noBackupFilesDir/scan-session.json` 保存最近一次会话，schemaVersion=1，使用 AtomicFile，最大 256 KiB。它是阶段 3 的有界状态记录，不是完整节点数据库，也不保存多会话历史。

独立 I/O 执行器串行写入并合并待写进度；同进程不同服务实例共用该执行器，避免旧实例收尾写入与新实例恢复相互覆盖。进入 JNI 前确保活动标记落盘；终态完成落盘后才向查询发布并允许下一个任务。因此页面读到 Completed 后，工作进程死亡不会把该记录误判为运行中。

服务恢复发现 queued/running/cancelling 或 active 标记时，改写为 Interrupted、complete=false，保留已有统计并记录结束时间。已持久完成的 Partial、Cancelled、Completed、Failed 等终态保持原状态。记录损坏或读写失败提供失败原因，不能把恢复错误当作空的成功结果。

快照包含会话 ID、路径、开始/更新时间和结束时间、状态/原因、工作 UID/PID、完整性、暂定标记、容量、问题数、FD 指标和预览。发现进度的容量尚未去重；终态使用阶段 2 的确定性硬链接去重和子树聚合。中断且未拿到原生终态时，FD 结束计数使用 -1 表示未知。

预览最多 64 个节点、64 个问题；各类路径字节预算为 64 KiB，路径过长或超额时标记 previewTruncated。节点、问题路径保留原始 hex；UI 对无法解码的 UTF-8 使用替换字符，并转义换行等字符。Flutter 不持有整棵树。完整树目前仍在 C++ 原型内存中，阶段 5 将替换为持久索引与分页。

不可枚举目录显示未完成/未知范围，数字只表示已知部分；根的 complete 取决于全部已覆盖范围，不能把 Partial/Cancelled/Interrupted 的数字当作完整零容量。

## 6. 主要文件

| 文件 | 职责 |
| --- | --- |
| `pigeons/scanner_api.dart` | 权限、开始/取消、状态及有界预览协议 |
| `lib/platform/scan_client.dart` | Flutter 平台适配及原始路径显示转换 |
| `lib/features/scan/presentation/scan_dashboard_page.dart` | 普通模式入口、权限引导、轮询、状态和预览 |
| `android/app/src/main/kotlin/com/example/disksize/MainActivity.kt` | 前台状态及运行时权限结果管理 |
| `android/app/src/main/kotlin/com/example/disksize/bridge/ScannerPlatformApi.kt` | Pigeon 网关、前台启动与快照转换 |
| `android/app/src/main/kotlin/com/example/disksize/scan/ScanAccess.kt` | API 29 / API 30+ 权限与路径能力 |
| `android/app/src/main/kotlin/com/example/disksize/scan/CoordinatorService.kt` | 单任务、前台服务、取消、超时、恢复与持久化调度 |
| `android/app/src/main/kotlin/com/example/disksize/scan/SessionJournal.kt` | 有界 AtomicFile 会话记录 |
| `android/app/src/main/cpp/jni/scan_bridge.cpp` | 原生会话注册、取消、JVM 进度和预览 JSON |
| `integration_test/scanning_test.dart` | 普通 UID 设备测试 |
| `test/scan_dashboard_test.dart` | 页面权限、部分结果、重连和销毁测试 |
| `tools/phase3/` | 主机驱动的前后台、超时与授权撤销验证 |

协议生成仍运行 `tools/generate-bridge.ps1`，Dart 与 Kotlin 生成代码一同维护；重新生成的哈希一致性检查通过。

## 7. 验证范围与已知限制

本次验收：

- Dart 静态分析通过，8 项页面/路径显示测试通过（包含原有运行环境测试）。
- arm64-v8a/x86_64 的正常入口 debug APK 构建通过，最终安装正常首页版本。
- 3 项设备集成测试通过：普通 UID 的 Partial/失败/权限检查；取消、真实服务进程 SIGKILL、页面销毁与终态持久恢复；真实 HOME/返回、共享目录完成和系统 dataSync 超时。运行时通知权限被拒绝，扫描仍正常执行。
- 独立主机授权撤销测试通过：正常首页选择受控路径启动扫描，撤销 AppOp 导致应用进程停止，重新打开后原 ID 恢复为 Interrupted/process-interrupted、active=false、complete=false。
- 8 组原生测试及阶段 0 固定样本交叉核对回归通过，包含新增 40 次外部线程取消检查。已知 root 样本仍为 169 节点、1,073,746,077 字节逻辑大小和 660,480 字节报告占用量。
- Pigeon 重新生成一致性检查通过；测试结束后所有文件访问 AppOp 恢复 default，dataSync 超时自定义值移除，SELinux 维持 Enforcing。
- Android Lint 通过：0 错误、4 警告，保留所有文件访问策略提示、固定 targetSdk 36 提示和模板资源/KTX 建议。正常首页已在真机检查，测试样本均已清理。

普通应用的部分扫描样本含 5 GiB 稀疏文件、5 字节 hello、2 字节 Unicode/换行名称文件、循环符号链接和受保护目录。结果逻辑大小为 5 GiB + 7 字节，受保护目录产生一条 PermissionDenied 并保持不完整。私有目录恢复样本含 450 层目录、21,600 个普通文件；完整结果为 22,051 节点、151,200 字节逻辑大小。共享前后台样本含 80 层目录、1,280 个文件；1,361 节点、8,960 字节逻辑大小符合预期。取得原生终态的扫描均验证结束扫描 FD 为 0。

Android Lint 使用 `tools/lint-android.ps1` 执行。该脚本仅规范忽略提交的 `android/local.properties` 的 Windows 盘符冒号转义，兼容 Flutter 生成文件和 Lint PropertyEscape 的差异，不修改共享 SDK 或把本机路径写入仓库配置。

最终正常入口 APK 为 `build/app/outputs/flutter-apk/app-debug.apk`，SHA-256：`82AD9CCA1EC753BEE7C38A0D5E5D19A2956F9E7A1A4C14965336876EC9AB54EC`；与 `.local/phase3/app-debug.apk` 一致，已安装并恢复首页。

复现步骤及脚本会临时修改的设备设置见[阶段 3 工具说明](../tools/phase3/README.md)。日志与正常入口 APK 保存在忽略提交的 `.local/phase3/`。

当前设备为 Android 16/API 36、arm64、4 KiB 页，SELinux Enforcing。API 29 的 legacy 分支、API 30～34 的实际授权与生命周期、x86_64 和 16 KiB 页设备仍需对应运行环境验证；本阶段双 ABI 构建不替代这些设备矩阵。

深层 FUSE 目录存在明显性能开销：一次 240 层、每层 16 个文件的受控共享目录扫描，4,081 节点的会话耗时约 53.4 秒。当前逐级重开并校验路径以限制 FD 的策略在这种情况下代价较高；不能据小型私有目录基准推断共享存储全卷性能。阶段 5 规模验收前应优化遍历路径与持久索引，保留挂载/链接边界验证和 FD 上限。

本阶段仅主共享存储提供专门入口与授权监测；自定义其他卷路径以实际普通权限扫描并记录错误。取消、进程恢复和终态预览已形成基础，root Binder/pipe、完整目录查询和快照对比依次留在后续阶段。

相关官方文档：[前台服务类型：dataSync 的本地文件处理](https://developer.android.com/develop/background-work/services/fgs/service-types#data-sync)、[前台服务超时及测试参数](https://developer.android.com/develop/background-work/services/fgs/timeout)、[所有文件访问](https://developer.android.com/training/data-storage/manage-all-files)、[Android 10 存储变更](https://developer.android.com/about/versions/10/privacy/changes#scoped-storage)、[通知运行时权限](https://developer.android.com/develop/ui/views/notifications/notification-permission)。
