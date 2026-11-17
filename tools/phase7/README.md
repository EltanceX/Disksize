# 阶段 7 树图验证

在仓库根目录加载 `.local/android-env.ps1`，设备需已允许 ADB 调试；本机已授权 shell su，安装使用 `-RootInstall` 避免系统安装器确认：

```powershell
flutter analyze
flutter test
./tools/phase7/verify.ps1 -Serial '<device-id>' -RootInstall
```

脚本构建正常入口双 ABI debug APK 和树图集成测试 profile APK，通过 `pm install -r` 覆盖安装，再从目标进程日志读取 Dart VM Service URL。使用动态 ADB 转发端口和 `flutter drive --use-existing-app` 附加测试，不经 Flutter 的设备测试安装/卸载流程。结束时移除转发并恢复正常 APK。未授权 shell su 的设备省略 `-RootInstall`，继续使用普通 ADB 安装。

profile 集成测试创建目录、零大小文件、稀疏文件、211 个根直接子项、`Projects/Build/Cache` 多层目录，以及含 120 个等大小文件的 Stress 子目录。核验真实索引的有界树图查询、两种容量口径、目录标题命中和按钮下钻返回，再双击 Stress 标题进入目录，在其 102 个矩形（含当前目录）上连续单击 40 次，采集 Flutter FrameTiming 和单层 100 节点布局的平均 CPU 耗时；每次单击在下一帧验证选中，无须等待双击判定窗口。返回根后，双击第 3 级目录色块直接进入，检查面包屑并逐级返回，再核验默认级别 3、切换 0/6/3，并截取级别 3 的嵌套树图。帧采样在后续双击下钻、深度切换和截图转换前结束，避免它们影响记录。帧报告是固定设备和这组交互的基线，不能替代其他设备或复杂目录的性能矩阵。

脚本为 profile 测试启用 `PHASE7_LIFECYCLE`，设备驱动等待应用选中目录后，实际展开/收起通知栏，再按 Home 并重新打开应用。测试记录 Flutter 生命周期及索引请求，验证选中项、画布实例和滚动位置保留，通知栏不重查树图，真正后台返回最多只检查一次会话版本。直接运行未带此 define 的集成测试会跳过需要主机配合的动作。设备驱动作业、ADB 转发和测试信号文件随测试结束清理。

输出：

- `.local/phase7/device-verify.log`、`drive.log`：构建、安装和测试结果。
- `.local/phase7/frame-report.json`：帧数、build/raster/total P95、默认三级查询耗时、单层布局均值、Stress 矩形数与有界查询条数、即时选中与双击下钻结果、通知栏/后台往返的生命周期和版本检查次数，以及最终截图的深度和嵌套矩形数。
- `.local/phase7/treemap.png`：profile 样本的默认级别 3 嵌套树图。截图先写入应用自有 external files 目录，再由 ADB shell 读取，兼容不可 `run-as` 的 profile 包。
- `.local/phase7/app-debug.apk`：恢复用正常入口 APK。

测试正常结束会清理自己的临时目录。若宿主中断或安装恢复提前终止了测试，应根据测试会话的精确根路径检查遗留样本，只清理该次创建的 `files/disksize-phase7-*` 目录，不清理应用扫描记录或其他目录。

独立 SQLite 回归包括 1,003 个直接子项、稀疏文件、共享硬链接、未知元数据、跳过挂载、两种口径与非法查询，以及相对 0～6 级、默认深度 3 和 10,101 节点样本的全局节点/分支/文本预算；每个测试使用独立数据库，不覆盖真实索引：

```powershell
./android/gradlew.bat -p android :app:assembleDebugAndroidTest :app:testDebugUnitTest
./tools/install-android-apk.ps1 -Serial '<device-id>' -Apk build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk -UseRoot
adb -s '<device-id>' shell am instrument -w -r -e class com.example.disksize.index.IndexStoreTest com.example.disksize.test/androidx.test.runner.AndroidJUnitRunner
```

运行前先安装当前正常入口 APK，确保测试 APK 与目标应用的 Kotlin 实现一致。Flutter 测试覆盖几何面积、边界、不重叠、目录标题与深层点击命中、各目录余量、极大计数、像素聚合、深度/口径切换、迟到响应、revision 变化、状态提示，以及 360×800 双倍字号和 1280×900 分栏布局。双击回归覆盖目录标题与深层目录进入、文件/灰色汇总区/当前目录不下钻、相邻不同色块不误进入和取消手势。
