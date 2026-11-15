# 阶段 2 原生扫描验证

先在仓库根目录加载 Android 环境，再执行：

```powershell
. ./.local/android-env.ps1
./tools/phase2/verify.ps1 -Serial '<device-id>'
```

脚本使用 `ANDROID_HOME`，也可传入 `-AndroidSdk`。它为 arm64-v8a 和 x86_64 构建同一 C++ 核心、CLI 与测试程序，然后推送设备 ABI 对应的程序到 `/data/local/tmp/disksize-phase2-bin` 并执行原生测试。日志与主机构建输出均在忽略提交的 `.local/phase2/`。

设备需已授权 ADB 与 `su`。测试以 root 创建独立的 `mkdtemp` 样本，包含硬链接、5 GiB 逻辑稀疏文件、FIFO 和异常字节文件名，结束时仅清理本次创建的样本。权限测试在子进程降至 UID/GID 2000；同设备 bind mount 测试仅在新建且设为 private 的挂载命名空间内执行，不能创建命名空间时报告 skipped，绝不在原命名空间内挂载。SELinux 保持设备原有策略。

可额外核验阶段 0 已保留且未修改的受控样本：

```powershell
./tools/phase2/verify.ps1 -Serial '<device-id>' `
  -BaselineRoot '/data/local/tmp/disksize-phase0/run-20261115-02' `
  -BaselineShared '/sdcard/Download/DiskSize-phase0/run-20261115-02'
```

这两个参数专用于阶段 0 的固定内容，脚本会断言已知条目和逻辑大小，并以独立 `find -exec stat` 的纯数字元数据核对 root 分配量。没有路径文本分割，也不使用 `du` 作为扫描后端。不要把任意用户目录传入这两个基线参数。

独立 CLI 示例：

```powershell
adb -s '<device-id>' shell /data/local/tmp/disksize-phase2-bin/disksize_scan `
  --root /data/local/tmp --workers 2 --queue 128 --batch 256 --nodes
# root 执行时必须将完整命令作为 su -c 的单个参数：
adb -s '<device-id>' shell "su -c '/data/local/tmp/disksize-phase2-bin/disksize_scan --root /data/local/tmp --events'"
```

参数还包括 `--max-depth N`、`--max-nodes N` 和 `--cancel-after N`（收到至少 N 个发现节点后请求取消）。默认不输出逐条节点；`--nodes` 输出最终节点，`--events` 输出发现批次和暂定进度。所有输出是 NDJSON；名称、相对路径和链接目标使用十六进制原始字节。汇总包含计数、容量、耗时、RSS、队列/线程/FD 峰值及完整性状态。

退出码：Completed=0，Partial=2，Cancelled=3，Failed=1，参数或未处理的运行异常=64。`Partial` 的数字只是已知范围，必须结合节点完整性和 issue 阅读。CLI 的 UID 2000 属于 ADB shell，不能视为普通应用的访问权限验证。

独立 CLI/测试静态链接 libc++；Android 应用仍采用现有共享 STL 与 JNI 配置。独立工具不会进入 APK。阶段 2 不新增 Flutter 扫描页面或 Android 扫描控制接口；后者在阶段 3 接入。
