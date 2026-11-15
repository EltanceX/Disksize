# DiskSize 第一版实现步骤

日期：2026-11-15

状态：阶段 0～4 已完成；阶段 5～9 待执行

设计依据：[第一版实现方案](./v1-design.md)

## 1. 实施原则与依赖顺序

先证明扫描口径正确、普通/root 后端可复用及大规模数据可承受，再完善可视化。

主线顺序：环境与骨架 → 扫描原型 → Android 服务与权限 → root 后端 → 索引和分页 → 目录 UI → 矩形树图 → 快照对比 → 集成验证。

各阶段的完成以验收结果为准。以下清单均为计划，尚未勾选的条目不代表已实现。

## 2. 阶段 0：准备可复现工具链

- [x] 补装 API 36 平台、NDK `28.2.13676358` 和 CMake `3.22.1`。
- [x] 使用 Flutter 3.47.9、Dart 3.13.7 和现有 JDK 21，检查 Android 构建链并成功构建 debug APK。
- [x] 记录实际 Flutter、Gradle、AGP、Kotlin、NDK 和 CMake 版本。
- [x] 准备 arm64 普通应用与 root 测试环境：同一 Xiaomi 真机已验证普通应用 UID 运行和 KernelSU root。
- [x] 建立设备与受控测试数据基线，包含系统版本、root 方案和文件系统类型。

验收结果见[阶段 0 环境基线](./environment-baseline.md)。本机后续开发可在仓库根目录执行 `. ./.local/android-env.ps1` 加载环境；复用脚本与样例见[阶段 0 工具说明](../tools/phase0/README.md)。

本机命令行工具已提供 `android.exe`；`sdkmanager.bat` 会转接到 Android CLI 并提示弃用。以下使用已核实的新命令和包标识，环境变量只作用于当前 PowerShell 终端：

```powershell
$env:ANDROID_HOME = 'I:\Software\Android\SDK'
$env:JAVA_HOME = 'D:\Software Environment\jdk-21.0.9'
$flutterBin = 'E:\Projects\.toolchains\flutter\bin\flutter.bat'
$androidCli = 'I:\Software\Android\SDK\cmdline-tools\latest\bin\android.exe'

& $androidCli "--sdk=$env:ANDROID_HOME" sdk install 'platforms/android-36'
if ($LASTEXITCODE -ne 0) { throw 'Android API 36 安装失败' }
& $androidCli "--sdk=$env:ANDROID_HOME" sdk install 'ndk/28.2.13676358'
if ($LASTEXITCODE -ne 0) { throw 'NDK 安装失败' }
& $androidCli "--sdk=$env:ANDROID_HOME" sdk install 'cmake/3.22.1'
if ($LASTEXITCODE -ne 0) { throw 'CMake 安装失败' }

& $androidCli "--sdk=$env:ANDROID_HOME" sdk list
& 'I:\Software\Android\SDK\cmake\3.22.1\bin\cmake.exe' --version
& $flutterBin doctor -v
```

预期安装目录分别为 `SDK\platforms\android-36`、`SDK\ndk\28.2.13676358` 和 `SDK\cmake\3.22.1`。现有 API 37 和 Build Tools 36.0.0 保留，平台可以并存。

如果 Flutter 检查输出中的 SDK 或 JDK 路径与计划不一致，可执行以下命令固定路径；这会修改 Flutter 的全局工具配置，而不是仅修改当前终端：

```powershell
& $flutterBin config --android-sdk 'I:\Software\Android\SDK' --jdk-dir 'D:\Software Environment\jdk-21.0.9'
& $flutterBin doctor -v
```

如在其他环境安装时要求接受许可证，应阅读后按工具提示处理。本机新版兼容脚本已注明无需单独执行 `sdkmanager --licenses`。上述安装命令保留用于复现；本机已完成安装，doctor 已确认全部 Android 许可证接受。

**验收：** Android 工具链检查通过，必要许可证已接受，版本记录完整；本机路径没有写入可提交的构建脚本。

**实测：** 两种 ABI 的原生探针构建成功，arm64 真机运行及权限差异验证通过；Flutter debug APK 构建、签名校验、真机安装启动和普通应用 UID 检查通过，临时验证应用随后已卸载。16 KB 页设备、其他 API 和正式应用权限行为保留在后续测试矩阵中。

## 3. 阶段 1：创建 Flutter / Android / C++ 骨架

- [x] 在当前仓库创建 Android 平台的 Flutter 应用，保留现有 `docs`、Git 忽略规则和属性规则。
- [x] 使用 `com.example.disksize` 开发占位包名，发布前确定正式 applicationId。
- [x] 将 minSdk 设为 29，compileSdk/targetSdk 固定为 36；锁定 NDK 28.2.13676358 和 CMake 3.22.1。
- [x] 接入 C++20 与 JNI，实现原生版本、进程 UID/PID 和简单自检接口。
- [x] 创建 Pigeon 协议，生成代码并记录再生成命令；重新生成的一致性检查通过。
- [x] 建立普通权限的 `CoordinatorService` 与 `:scanner` 进程边界，真机确认同 UID、不同 PID。
- [x] 定义扫描配置、会话状态、取消与错误类型，搭建原生自检页面及失败重试入口。

应用生成命令示例：

```powershell
& 'E:\Projects\.toolchains\flutter\bin\flutter.bat' create --platforms=android --project-name disksize .
```

建议目录布局，作为后续创建代码的参考：

```text
lib/
  app/
  features/
    scan/
    explorer/
    treemap/
    snapshots/
  platform/
pigeons/
android/app/src/main/
  aidl/
  kotlin/<package>/
    bridge/
    scan/
    root/
  cpp/
    core/
    jni/
    protocol/
native_tests/
integration_test/
docs/
```

**验收：** debug APK 能安装运行；Flutter 经 Pigeon、服务和 JNI 读到原生自检结果，服务进程 UID 是普通应用 UID。

**实测：** 静态分析、三项页面测试、真机跨进程集成测试均通过；双 ABI debug APK 已构建并安装运行。APK 确认仅包含 arm64-v8a/x86_64，非导出服务拒绝外部 shell 启动，ZIP 与自编译 JNI 库的 16 KB 对齐检查通过。完整结果见[阶段 1 骨架与验收](./phase1-skeleton.md)。

## 4. 阶段 2：实现只读扫描核心原型

- [x] 实现基于真实路径和目录 FD 的遍历、节点标识及批量回调。
- [x] 定义逻辑大小、报告占用量、目录自身占用及硬链接归属规则。
- [x] 实现符号链接处理、挂载边界、伪文件系统排除、深目录及 FD 上限。
- [x] 实现有界任务队列、两个初始遍历线程、取消检查和错误分类。
- [x] 使用合成文件树验证正常文件、稀疏文件、硬链接、链接循环与异常名称。
- [x] 输出机器可读的统计摘要与基准数据；不把设备 `du` 文本作为扫描后端。

**验收：** 已知内容和分配量的受控样例符合定义；不可访问项保持未知状态；无链接循环、重复挂载累计、线程栈耗尽或 FD 泄漏。

**实测：** 8 组原生真机测试全部通过，含同设备 bind mount、超过 PATH_MAX 的深目录、低 FD 上限、取消、权限拒绝及路径替换。阶段 0 样本的 169 个节点、1,073,746,077 字节逻辑大小、660,480 字节报告占用量与已知内容及独立 stat 汇总一致。双 ABI 原生/Android 构建、现有 JNI 集成回归通过。完整口径、原型限制和基准见[阶段 2 扫描核心与验收](./phase2-scanner.md)。应用扫描入口在阶段 3 接入。

## 5. 阶段 3：打通普通模式与 Android 生命周期

- [x] 实现自身目录和共享存储扫描入口、权限状态检测及引导。
- [x] 分别实现 API 29 读取/legacy 分支和 API 30+ 所有文件访问分支。
- [x] 为扫描配置适用的前台服务类型、通知、可取消任务和页面重连机制。
- [x] 将扫描移至服务工作线程，Pigeon/Binder 调用只做命令投递及查询。
- [x] 实现进度合并，开始、取消、失败、部分完成及中断状态持久化。
- [x] 处理前后台切换、服务死亡、权限被撤销和前台服务超时。

**验收：** 权限不足时可浏览已知结果并看到错误范围；切换页面或应用前后台不会误报扫描完成；取消后停止接收新任务并释放资源。

**实测：** 普通应用 UID 的自身/共享目录扫描、Partial 和有界预览、取消、前后台切换、真实服务进程死亡及持久恢复均通过。主机驱动撤销所有文件访问并重开应用，确认 Interrupted；缩短系统 dataSync 时限触发真实 onTimeout，正确停止并保留中断原因。双 ABI 构建、8 项 Flutter 测试、3 项设备集成测试和原生回归通过。API 29/30～34 分支已实现，当前运行证据来自 API 36；完整节点分页和其他设备矩阵留在阶段 5/9。见[阶段 3 普通模式与生命周期](./phase3-ordinary-lifecycle.md)。

## 6. 阶段 4：接入 root 后端

- [x] 接入并锁定 libsu 版本，实现显式 root 请求、拒绝处理和服务绑定。
- [x] 在 RootService 加载同一扫描原生库，验证实际执行 UID 和目标目录访问。
- [x] 使用 Binder 控制及二进制 pipe 传输结果，验证协议版本、长度和会话 ID。
- [x] 处理文件名中的 Unicode、空格、换行和无法解码的原始字节。
- [x] 实现 root 进程退出、pipe 断开、取消和客户端死亡的清理逻辑。
- [x] 基于扫描进程的挂载视图选择入口，检查共享存储映射、多用户和加密状态。
- [x] 固定普通应用 UID 的存储写入边界并实测会话文件所有权；尚未实现的数据库、导出及缓存继续遵循该边界。

**验收：** root 可访问路径的数据来自实际 root 工作进程；拒绝授权可切回普通模式；服务死亡可恢复 UI；扫描应用私有目录时不改变被扫描文件权限或所有权。

**实测：** libsu 6.0.0 已锁定；KernelSU 真机的显式授权/拒绝、UID=0 JNI、受保护样本及原始名称、共享存储双入口、取消、真实 root/客户端死亡和重连均通过。独立 stat 容量与扫描前后所有权核对一致；二进制协议异常测试、原生管道断开/背压/超时及既有回归通过。多用户目录和当前用户解锁状态可查询，真实锁定用户及其他 root 方案仍在后续矩阵中验证。完整协议、验收和限制见[阶段 4 Root 后端](./phase4-root-backend.md)。下一步是阶段 5 的索引、聚合与分页。

## 7. 阶段 5：建立索引、聚合与分页查询

- [ ] 建立会话、节点、聚合、错误与快照表及 schema 版本。
- [ ] 实现单写入端、事务批量导入和结果背压，避免内存无界增长。
- [ ] 完成硬链接确定性归属和最终聚合，区分暂定统计与最终统计。
- [ ] 实现子目录、大文件、详情和错误列表查询；为常用排序建立索引。
- [ ] 分页加入稳定并列键，处理相同大小节点和查询期间的会话状态变化。
- [ ] 逐级测量约 1 万、10 万和 100 万条目的扫描、写入、查询及内存表现。

**验收：** Dart 不持有整棵树；工作队列、传输缓冲和页面缓存均有界；分页无漏项或重复项；记录固定设备上的性能基线和已知限制。

## 8. 阶段 6：完成目录与大文件界面

- [ ] 实现卷入口、访问模式、权限状态、扫描状态和最近会话展示。
- [ ] 实现面包屑、下钻、返回、排序、分页和逻辑大小/报告占用量切换。
- [ ] 展示不可访问、扫描未完成、共享链接与主动跳过状态。
- [ ] 实现当前范围的大文件列表、详情与路径复制。
- [ ] 展示卷统计与扫描统计的差额，并说明适用范围和统计时间。

**验收：** 在相同数据口径下，列表、详情和汇总相互一致；长名称和大字体可正常展示；扫描中查询不会阻塞界面线程。

## 9. 阶段 7：实现矩形树图

- [ ] 以当前目录有限范围的数据实现 squarified treemap 布局。
- [ ] 使用 `CustomPainter` 绘制，支持点击命中、选中和下钻返回。
- [ ] 按像素面积聚合过小节点，限制布局节点数和标签数量。
- [ ] 切换逻辑大小/报告占用量时复用同一统计语义。
- [ ] 对过期会话、未完成数据和零容量目录提供明确状态。

**验收：** 图形面积与所选容量口径一致；大目录不会产生数量失控的 Widget 或布局对象；完成固定设备上的帧耗时与交互检查。

## 10. 阶段 8：实现快照与增长对比

- [ ] 将完成扫描保存为不可变快照，部分扫描以独立状态保存。
- [ ] 校验卷、用户、根路径、扫描模式及统计规则的可比性。
- [ ] 按相对路径计算新增、删除、容量变化和增长最多的目录。
- [ ] 对不可访问范围和部分结果显示未知，避免误报为删除。
- [ ] 记录重命名按删除和新增展示的首版限制。
- [ ] 实现快照保留及删除功能，仅删除应用自己的索引和快照记录。

**验收：** 对受控的新增、删除和大小变化给出预期差额；重新排列扫描任务不会改变硬链接的最终归属；不兼容快照有明确说明。

## 11. 阶段 9：集成验证与首版交付

- [ ] 完成受控文件树的 C++ 扫描与聚合测试。
- [ ] 完成 Android 普通/root、权限撤销、服务死亡、取消和后台限制集成检查。
- [ ] 完成 Flutter 关键交互测试和真机人工验收。
- [ ] 核对同权限、同视图、同大小口径下的参照工具结果。
- [ ] 验证 arm64-v8a、x86_64 构建及 4 KB/16 KB 页兼容性，检查全部打包原生库。
- [ ] 运行静态分析、已有测试和 release APK 构建，记录结果。
- [ ] 更新文档，列出实际支持范围、性能基线和未完成事项。

项目具备对应测试后执行：

```powershell
& 'E:\Projects\.toolchains\flutter\bin\flutter.bat' analyze
& 'E:\Projects\.toolchains\flutter\bin\flutter.bat' test
& 'E:\Projects\.toolchains\flutter\bin\flutter.bat' build apk --release
```

原生与设备集成测试应补充独立、可复现的运行说明，不能只依赖 Flutter 单元测试。release 构建成功不等于发布签名、商店权限审核或发布操作已完成。

**第一版完成条件：** 普通模式独立可用、root 模式可选且可恢复；扫描统计符合定义；目录、树图和快照对比一致；规模测试结果已记录；不存在扫描导致的目标文件内容、权限或所有权修改。

## 12. Git 与仓库约定

- 提交源码、文档、构建配置、Gradle Wrapper 和应用的 `pubspec.lock`。
- 忽略 SDK 本机路径、工具链、构建目录、签名材料和扫描数据导出。
- C++、Kotlin、Dart、Markdown 和配置文件使用 UTF-8；通过 `.gitattributes` 固定常见文本换行策略。
- 每阶段按可验证的功能提交，提交说明记录行为与验证结果。
- 本次仓库初始化只建立 Git 元数据和初始文件，不预设远程仓库或发布流程。
