# Root 扫描的应用数据挂载视图

日期：2026-11-15

## 原因与修改

KernelSU/API 36 真机上，原 Root 服务虽为 UID/EUID 0，仍继承普通应用的挂载命名空间。该视图用 tmpfs 覆盖 `/data/data`，仅把应用自身及两个获准的应用目录绑定进来。扫描器按实际 mount ID 检测边界，三个子目录均被标记为 `mount-boundary`，最终只有 4 个节点、0 B，状态为 Partial。全局视图中的同一路径实际位于 F2FS。

这台设备的 `/data/user/0` 是指向 `/data/data` 的符号链接，因此更换路径别名也会进入相同的隔离视图。UID 0 只能证明 root 身份，不能证明已经获得完整挂载视图。

`RootBackend` 在 companion 初始化时调用 `Shell.setDefaultBuilder`，配置 `Shell.FLAG_MOUNT_MASTER`。配置每个普通 coordinator 进程只执行一次，早于 libsu 主 shell 创建，服务在同一进程内重建时也不会重复配置。此操作只设置 builder，不执行 su；普通启动及读取 root 状态继续不请求授权。

用户显式请求 root 后，libsu 优先通过 `su --mount-master` 创建主 shell，RootService 从该 shell 启动并继承其挂载视图。继续记录实际 root 身份、命名空间和入口文件系统。保留原生不跨挂载、符号链接及硬链接统计规则，避免通过放开挂载遍历来重复计数。

libsu 6.0.0 在 su 不支持 mount-master 时会回退普通 su；因此该配置表达优先使用全局视图，并不保证所有 root 实现均支持。当前实测仅覆盖 KernelSU。真实用户锁定与 fscrypt 可读性仍须独立判断。

## 设备回归

新增 opt-in instrumentation `IndexDeviceTest#rootAppDataUsesGlobalMountNamespace`：

- 独立读取 PID 1 的挂载命名空间并作为预期值，核对实际 Root 服务命名空间及 UID/EUID。
- 核对当前用户 CE 入口可读，且不再是隔离用的 tmpfs。
- 创建普通应用自有的微型样本，比较普通扫描、root 原路径和 CE 别名的节点数、逻辑大小、报告占用及 FD 释放。
- 通过真实 Binder → native pipe → 普通 UID SQLite 链路扫描 `/data/data`，验证实际子项可进入，结果包含非零容量且不是 tmpfs。实时应用目录可能变化，Completed 或 Partial 均可接受，不把变化误判为完整扫描。

此测试只读应用元数据，不读取其他应用的文件内容。测试会写入 DiskSize 自己的索引并清理自己的样本；运行前应结束已有扫描。默认测试集跳过此项，只有传入 `rootAppData=true` 才扫描实际应用数据目录。

```powershell
. ./.local/android-env.ps1
flutter build apk --debug --target-platform 'android-arm64,android-x64'
./android/gradlew.bat -p android :app:assembleDebugAndroidTest :app:testDebugUnitTest
./tools/install-android-apk.ps1 -Serial '<device-id>' -Apk build/app/outputs/flutter-apk/app-debug.apk -UseRoot
./tools/install-android-apk.ps1 -Serial '<device-id>' -Apk build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk -UseRoot
$expectedNamespace = (& adb -s '<device-id>' shell "su -c 'readlink /proc/1/ns/mnt'").Trim()
adb -s '<device-id>' shell "am instrument -w -r -e class com.example.disksize.index.IndexDeviceTest#rootAppDataUsesGlobalMountNamespace -e rootAppData true -e rootMountNamespace '$expectedNamespace' com.example.disksize.test/androidx.test.runner.AndroidJUnitRunner"
```

使用覆盖安装保留应用数据，不通过会卸载目标包的 Flutter 设备测试运行器执行此回归。日志、修改前索引备份及截图保存在本机忽略目录 `.local/root-namespace/`。

## 实测结果

Xiaomi sheng、Android 16/API 36、KernelSU、arm64、SELinux Enforcing：

- Root 服务与 PID 1 均为 `mnt:[4026532508]`；实际 UID/EUID 均为 0。CE 入口恢复为 F2FS。
- `/data/data`：188,441 个节点，18,475,537,774 逻辑字节（17.21 GiB），18,971,312,640 报告占用字节（17.67 GiB）；Completed、complete=true、0 个问题、结束时 0 个扫描 FD，根挂载 ID 239。native 扫描与传输约 20.66 秒，整项设备测试含入库聚合约 37.20 秒。
- 已打开正常应用的该会话并检查截图：容量汇总、应用目录、排序和容量条显示正常。普通启动仍显示“Root 访问尚未请求”。
- 4 项 JVM 协议测试通过；既有索引/设备回归报告 `OK (12 tests)`，其中执行 10 项、跳过未启用的规模及真实应用数据项；真实应用数据专项另行 `OK (1 test)`。
- 原生扫描 8 项与流式扫描 8 项通过，包含同设备 bind mount 边界，确认跨挂载保护仍有效。
- 正常入口双 ABI debug APK 已通过 `su pm install -r` 覆盖安装；原有索引未清除，修改前约 97.5 MB 的 `no_backup` 另有本机备份。
