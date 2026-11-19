# 局部重扫步骤 5：目录与快照界面

日期：2026-11-18。

后续状态（2026-11-19）：[步骤 6](./snapshot-subtree-rescan-acceptance.md) 已完成性能、恢复及日常包交付；下文保留步骤 5 当时的实现和验证记录。

状态：步骤 5 已实现，Flutter 交互/布局测试及隔离真机界面流程已通过。schema 保持 5，扫描协议保持 v4，沿用步骤 4 的优化扫描与服务。手机日常版仍恢复为原优化 Profile 包；包含新界面的日常包交付和大历史快照性能测量留给步骤 6。

## 操作入口与范围确认

- 目录列表的文件夹操作菜单、树图选中文件夹后的按钮、目录详情，以及当前目录统计区均可发起“重新扫描此文件夹”。快照根目录也可操作，更新同一份快照。
- 文件、符号链接和特殊文件不提供目录重扫；未最终聚合的会话不提供有效入口。全局 Partial 的快照仍可局部修复；本次子树结果为 Partial 时拒绝合并。
- 确认框展示完整的转义路径、原普通/Root 后端、包含全部后代、替换原子树且无法撤销的语义。桥接仍只传 snapshot ID、node ID 和 expected revision，显示路径不参与扫描定位。
- 已保留快照禁用入口，并说明到快照列表取消保留。服务仍重新验证保留、版本和身份，处理确认框停留期间的变化。
- 原“刷新索引”改名“重新加载索引”，只读数据库；界面另行说明它与重新扫描的区别。详情的刷新也改名为“重新加载详情”。

主要入口：[ExplorerPage](../lib/features/explorer/explorer_page.dart)、[ExplorerDetail](../lib/features/explorer/explorer_detail.dart)、[TreemapPanel](../lib/features/explorer/treemap_panel.dart)。

## 任务显示、取消与重连

[SnapshotRescanController](../lib/features/explorer/snapshot_rescan_controller.dart) 独立于最近全量扫描状态，驱动 [SnapshotRescanPanel](../lib/features/explorer/snapshot_rescan_panel.dart)：

- 显示准备、扫描、校验、应用更新、完成及失败阶段、范围、条目数和问题数；使用不定进度条，不推测总量或百分比。
- 扫描期间可浏览旧 revision；应用期间说明当前仍是旧版，提交后统一重新加载。
- 取消请求期间禁用重复点击，并显示正在确认结果。只有服务实际返回 cancelled 才显示取消；提交抢先完成则显示“更新已提交”。
- 状态查询串行，每次返回后再安排下一次；后台停止定时轮询，回前台立即查询。查询连接失败保留既有任务，并明确结果尚未确认，不将连接异常当作回滚或取消。
- 索引元数据提供有界的 `latestRescanTaskId`，退出目录页、Flutter 页面重建或应用重新启动后可重新发现持久任务；不会自动再次调用 start。服务死亡恢复仍由步骤 4 的持久任务状态决定。
- busy、retained、stale、目标消失/类型变化、身份/boot 不兼容、Root 连接不可用、新扫描不完整等错误有各自的中文说明和后续操作。

Root 不可用时说明回扫描首页显式连接；不会为重扫静默申请 su 或降级后端。同 boot 的首版限制不变。

## 提交后的游标、目录与详情

[ExplorerController](../lib/features/explorer/explorer_controller.dart) 在内容 revision 变化后：

1. 使旧页面请求失效，丢弃全部分页游标，重新查询第一页；排序、容量口径、目录/大文件/树图模式和树图深度保留。
2. 用缓存的原始相对路径定位当前目录；旧后代 ID 已删除时查找新 ID，目录消失或变为文件则退到最近仍存在的目录，并提示用户。
3. 树图选择按原始路径恢复；异步恢复绑定当前导航和 revision，不覆盖后来发生的导航。路径未精确匹配时清空选择。
4. 关闭旧版本的手机详情面板，宽屏详情按版本重建，旧问题游标随之失效。重新加载期间不展示未经当前页面验证的宽屏详情。
5. 外部打开前核验显示版本和条目是否仍存在；发现变化要求重新选择，避免复用旧引用。服务端继续按索引重新查询路径，后代 ID 不复用的约束保留。

新增只读 `IndexQueryKind.locate`：以目录 ID 为锚，传原始相对组件的 hex，在数据库 BLOB 路径索引内定位，不读取文件系统。每次最多 6000 个 hex 字符；长路径按完整组件分块，调用仍在现有 8192 字符桥接限制内。每块均绑定 expected revision，遇到缺失返回最近祖先；不通过有损 UTF-8、显示文本或字符串前缀匹配定位。

`expectedRevision` 也可用于 detail/issues 等索引查询。最终读区间只查询 session revision 复核，避免为了复核重复组装局部更新历史。

## 时间与快照、对比语义

索引/快照元数据新增的展示字段均从已有 schema 5 表读取，不迁移或改写原扫描时间：

| 字段 | 用途 |
| --- | --- |
| snapshotId、retained | 可操作性与保留提示 |
| latestRescanTaskId | 重新发现最近任务，包含失败或取消记录 |
| lastLocalUpdateAtMillis、localUpdateCount | 最近成功提交时间与成功次数 |
| lastLocalUpdate | 最近成功的原始范围、路径截断标记、扫描起止、提交完成与 revision |

范围展示最多 4096 原始字节，超长明确标记仅显示前段；历史摘要查询不加载完整路径。最近失败或取消不会取代最近成功更新的范围。任务发现按创建时间及插入顺序排序，成功摘要按 committed revision 排序。

目录、条目详情、快照卡片及对比双方复用 [LocalUpdateSummary](../lib/features/snapshots/local_update_summary.dart)，显示“已局部更新”、时间、次数、内容版本，并可展开范围及扫描/提交时间。明确说明各范围采集时间不同，其余目录沿用原记录，更新时间不代表全盘状态。

从浏览索引返回快照列表时重新查询列表；列表和对比页回前台也重新读取。对比下一页收到 stale-cursor 时丢弃旧游标，保留排序/筛选，重新请求第一页；busy 时显示等待任务结束后重新对比，不保留旧差额冒充新结果。

## 验证记录

2026-11-18，Windows 开发环境及 Xiaomi sheng / Android 16 / arm64。

| 项目 | 实际结果 |
| --- | --- |
| Flutter analyze | 无问题 |
| Flutter 全量测试 | 92 项通过、0 失败、0 跳过；新增 23 项界面/状态/定位测试 |
| 布局与图片检查 | 360×800、2 倍字号及 1280×900、正常字号；确认、进度、完成、快照卡片均验证，中文/Material 图标截图已检查 |
| JVM | 13 项通过 |
| Android 索引/持久化/合并/普通和 Root 服务回归 | 78 项报告中 77 项通过，1 项 benchmark 专用用例预期跳过 |
| 真机 Flutter 界面集成 | 1 项通过，执行真实确认框、Pigeon、前台服务、扫描、合并及重新进入页面 |
| 普通包构建 | Debug/Profile、arm64/x86_64 构建成功，两个测试开关均为 false |
| Android lint / diff | lint 0 错误、9 项既有警告；git diff --check 通过 |

Flutter 新用例覆盖：列表/树图/详情/根入口与确认、重新加载无扫描副作用、重复提交保护、保留/文件限制、busy/stale 错误、Partial 的两层语义、取消与提交竞态、旧详情关闭、后台/页面重建重连、暂时断连、查询不重叠、原路径恢复到新 ID 或祖先、树图选择恢复、旧版本外部打开拒绝、异常字节长路径分块、对比旧游标和混合时间说明、大字号和宽屏。

Android 新用例覆盖任务发现、原始扫描时间保留、最近成功历史与后来失败的隔离、4096 字节展示上限，以及原始异常字节定位、近似前缀隔离、文件/消失目录回退、非法组件和 stale revision。

真机界面测试先在自有临时目录创建完整快照，然后删除旧文件、创建新文件，经目录菜单确认重扫。验证快照 ID 不变、revision 仅增加一次、新内容和容量可查询、旧后代 ID 不可用、旧 revision 被拒绝、原始扫描起止不变、没有新增快照，重新进入页面仍显示完成记录。

记录位置：

- `.local/snapshot-rescan-step5-flutter-final.jsonl`
- `.local/snapshot-rescan-step5-layout-tests.jsonl` 及 `.local/snapshot-rescan-step5-ui-captures/`
- `.local/snapshot-rescan-step5-device-20261118-02/core/summary.json`
- `.local/snapshot-rescan-step5-ui-device-20261118-05/summary.json`
- `.local/snapshot-rescan-step5-production-build.log`
- `.local/snapshot-rescan-step5-lint-final.log`

早期设备回归中的整段 session JSON 不变断言已更新：新增任务发现字段允许变化，原内容和 journal 仍逐项保持不变。UI 集成测试最初没有等待异步查询后的确认框，随后又误用扫描前的 alias 路径筛选 canonical 根；复核时也修正了宽屏同时显示目录和详情摘要时的数量断言。均为测试准备/期望问题，最终使用实际界面状态、索引根和可变布局验证通过。截图用中文和 Material 图标字体，等待弹窗退场后再捕获；字体与捕获仅用于测试，不进入产品依赖。首次 lint 与 Flutter 检查并行时，本机 ignored local.properties 又被生成了未转义 drive colon；Flutter 检查结束后串行运行既有 lint 脚本通过，没有抑制检查或更改依赖。

## 复现及隔离

```powershell
. ./.local/android-env.ps1
flutter analyze
flutter test
./tools/snapshot-rescan/verify-step5.ps1 -Serial '<device-id>' -RootInstall -RootTests
```

[verify-step5.ps1](../tools/snapshot-rescan/verify-step5.ps1) 分开运行核心服务回归和专用 Flutter 集成入口。两次均备份/恢复主包及测试包，并核对正常主库、WAL 和 journal 的 SHA-256。UI 入口同时要求 Dart 编译标记、Android `RESCAN_TEST_HOST=true` 和显式 instrumentation 参数；数据库及 journal 固定使用步骤 4 的 DE 测试库，普通/Profile/Release 默认开关为 false。不要直接把该 integration target 安装为日常应用运行。

仅重跑 UI 可使用 `verify-step2.ps1 -IncludeService -UiOnly`；该模式与 RootTests 分开，仍有完整备份/恢复保护。测试删除限于本次自有样本和隔离快照，不卸载主应用、不清空数据、不扫描真实 `/data/data`。

本次设备恢复的原优化 APK SHA-256 仍为 `1ee4d9aeae2a8084a106cb2e6f0061e7d8ecfa3fa513734e254690a4518054d5`，正常索引/journal 指纹前后一致。

## 下一步

步骤 6 执行完整正确性/性能矩阵、真实较大历史索引的全局 SQL 合并与 WAL/空间/查询延迟测量，再将关闭两个测试开关的优化普通包覆盖安装并核对实际 APK 指纹。步骤 5 的受控界面验证不代替大型快照性能验收；本步没有对用户现有快照执行重扫。
