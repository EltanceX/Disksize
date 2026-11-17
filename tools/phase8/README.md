# 阶段 8 快照验证

在仓库根目录加载本机 Android 环境后执行：

```powershell
. ./.local/android-env.ps1
flutter analyze
flutter test
./tools/phase8/verify.ps1 -Serial '<device-id>' -RootInstall
```

脚本构建并保留正常入口双 ABI debug APK，安装快照集成测试 profile APK，再用 flutter drive --use-existing-app 附加已启动的进程。不会经 Flutter 的卸载重装流程。未授权 shell su 时省略 -RootInstall，使用普通 ADB 安装。要求 PowerShell 7.4+ 以保持 PNG 重定向的二进制数据。

集成测试仅在应用自身目录创建 disksize-phase8-* 临时样本；真实扫描两次，确认冻结容量、新增、删除、增长、保留保护、索引浏览和删除边界。通过仅暴露本轮快照 ID 的客户端适配器验证“一键删除快照”：默认选择非保留记录，执行后保留快照和原文件仍在，当前 journal 被清除；底层使用真实 Pigeon、Binder 和 SQLite。历史快照不进入测试选择范围。正常结束清理该次样本及两份测试快照。脚本 finally 恢复正常入口 APK 并移除 ADB 端口转发。

输出位于忽略目录 .local/phase8/：drive.log、comparison-report.json、comparison.png、batch-selection.png 和恢复用 app-debug.apk。报告包含 `batchDefaultSelection`、`batchRetainedPreserved` 和 `batchDeletedCount`。主机可将脚本输出另存为 .local/phase8-device-verify.log。

Flutter 批量删除回归覆盖默认全选跨页、逐项排除与自行选择、取消确认和返回退出选择、保留状态变化、部分失败后重试、删除中禁用重复操作，以及 360×800 双倍字号和 1280×900 布局。

若测试被外部终止，先从测试日志或快照记录确认其精确根路径，再可用 -CleanupRoot '<完整路径>' 重跑。测试会校验目标确为应用自身目录下的一层 phase8 样本目录，仅删除根路径完全匹配的测试快照和该目录；不要传入用户数据路径或父目录。

独立索引回归使用单独数据库，不覆盖真实索引：

```powershell
./android/gradlew.bat -p android :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest
./tools/install-android-apk.ps1 -Serial '<device-id>' -Apk build/app/outputs/apk/debug/app-debug.apk -UseRoot
./tools/install-android-apk.ps1 -Serial '<device-id>' -Apk build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk -UseRoot
adb -s '<device-id>' shell am instrument -w -r -e class com.example.disksize.index.IndexStoreTest,com.example.disksize.index.SnapshotStoreTest com.example.disksize.test/androidx.test.runner.AndroidJUnitRunner
./tools/lint-android.ps1
```

测试覆盖真实文件变更、部分快照、重排硬链接、类型替换、原始字节名称、旧 schema、游标并列键、保留与删除、构建恢复和深路径响应预算。完整语义与限制见[阶段 8 验收](../../docs/phase8-snapshots.md)。
