# 阶段 4 验证工具

从仓库根目录、已加载 Android/Flutter 环境的 PowerShell 7 执行。

```powershell
. ./.local/android-env.ps1
flutter analyze
flutter test
./android/gradlew.bat -p android :app:testDebugUnitTest
flutter build apk --debug --target-platform 'android-arm64,android-x64'
New-Item -ItemType Directory -Force .local/phase4 | Out-Null
Copy-Item build/app/outputs/flutter-apk/app-debug.apk .local/phase4/app-debug.apk
./tools/phase4/verify.ps1 -Serial '7b9a982f'
./tools/lint-android.ps1
```

`verify.ps1` 是当前 Xiaomi 平板、中文 KernelSU 3.3.0 管理器和 3048×2032 横屏布局的设备驱动，包含两个有截图依据的搜索/导航坐标，不能直接用于其他管理器或屏幕布局。它会在测试应用显式请求 su 后，仅为 `com.example.disksize` 打开管理器的应用授权，回到测试 Activity 并写入普通 UID 的同步标记。不同设备应替换该授权驱动，应用后端不依赖这些坐标。

Flutter 集成测试可能卸载测试 APK，导致 UID 改变及 KernelSU 授权丢失。因此先保存正常入口 APK；驱动在 finally 中通过 `tools/install-android-apk.ps1 -UseRoot` 恢复它，使用已授权的 ADB shell su 避免系统安装器弹窗，不把测试 runner 留作启动入口。新安装的正常应用可能需要用户再次在 root 管理器中允许 DiskSize。脚本不会变更其他应用授权、SELinux、全局挂载命名空间、所有文件 AppOp 或前台服务时限。

设备必须保留阶段 0 的只读基线：

```text
/data/local/tmp/disksize-phase0/run-20261115-02
/data/media/0/Download/DiskSize-phase0/run-20261115-02
/storage/emulated/0/Download/DiskSize-phase0/run-20261115-02
```

`create-fixture.sh` 在固定 `/data/local/tmp/disksize-phase4` 创建独立、root 所有且模式 700 的受控样本，含无效 UTF-8、换行、中文、空格、硬链接、符号链接、1 GiB 稀疏文件和受保护目录。只允许重建带 `.disksize-owned-fixture` 标记的同名目录。独立参照仅输出数字 stat 元数据，不拆分文件名；按 `(dev,ino)` 去重、按目录自身分配量和普通文件逻辑长度汇总。`find/stat` 只用于主机参照，不是应用扫描后端。

测试驱动核对普通/root 身份、原始名称、容量和预览，执行取消、杀 root 进程、杀普通客户端、重连和所有权检查。杀 root PID 前验证其 UID=0 且 cmdline 属于 disksize；客户端死亡后确认该 root PID 消失。成功后核对扫描前后元数据，并删除本阶段有标记的临时样本，阶段 0 基线保留。

日志位于忽略提交的 `.local/phase4/`：`root-integration.log`、`fixture-stat-before.txt`、`fixture-stat-after.txt`、`client-cleanup.txt` 和管理器 UI XML。失败时检查日志；异常退出可能保留带标记的样本，下一轮可安全重建。

独立原生管道测试复用阶段 2 的双 ABI CMake 构建目录：

```powershell
./tools/phase2/verify.ps1 -Serial '7b9a982f'
adb -s '7b9a982f' push .local/phase2/native-arm64-v8a/disksize_root_pipe_tests /data/local/tmp/disksize-phase2-bin/
adb -s '7b9a982f' shell chmod 755 /data/local/tmp/disksize-phase2-bin/disksize_root_pipe_tests
adb -s '7b9a982f' shell su -c /data/local/tmp/disksize-phase2-bin/disksize_root_pipe_tests
```

3 组测试覆盖真实 pipe 的 EPIPE、写端背压时取消和消费者停读超时；最后一组预期约 10 秒。JVM `RootProtocolTest` 单独覆盖恶意/损坏帧拒绝与原始名称保真。完整实现和限制见[阶段 4 验收文档](../../docs/phase4-root-backend.md)。

阶段 5 后，JVM 测试保留验证 v1 参考协议；应用增量协议在阶段 6 升为 v3，由 Android instrumentation 测试覆盖，见[阶段 6 工具说明](../phase6/README.md)。此脚本让 Flutter 测试在后台独立写日志，授权 UI 驱动每 100 ms 处理新增事件，避免授权导航阻塞日志消费和错过 root 进程中断时机。
