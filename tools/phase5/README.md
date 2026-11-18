# 阶段 5 设备验证

在项目根目录加载 Android 环境后运行：

```powershell
. ./.local/android-env.ps1
./tools/phase5/verify.ps1 -Serial '<device-id>' -Scales @()
# 可选：设备已允许开发包 root，且保留本项目阶段 0 样本时：
./tools/phase5/verify.ps1 -Serial '<device-id>' -RootBaseline
# root 后端的独立规模测量：
./tools/phase5/verify.ps1 -Serial '<device-id>' -Backend root -Scales @(100000)
```

脚本构建双 ABI dashboard APK 和独立 instrumentation APK，用 `adb install -r` 保留应用 UID。
使用 `am instrument` 直接运行 AndroidX 测试，检查实际 JUnit 结果；ADB 退出码本身不能证明测试通过。
不依赖 Gradle UTP 的主机测试执行组件，避免额外下载测试调度器。
Flutter 的 integration_test 依赖固定了 AndroidX test 1.3.0；测试 manifest 补充当前 target SDK 要求的 exported 属性。

默认依次生成 10,001、100,001、1,000,001 个真实节点。
目录数量分别为 100、1,000、1,000，每目录普通文件数为 99、99、999；额外加扫描根目录。
文件为零长度，既减少占用，也保证大文件排序有大量相同键。
扫描走真实 CoordinatorService、JNI、pipe、SQLite 和 Binder 查询；逐页用 BitSet 核对所有文件 ID，无遗漏及重复。
每页 100 条，仅保留当前游标和约 125 KiB 的百万节点核对位图。
测量不包含样本生成、分页总耗时和清理；首/末页延迟包含 Binder 往返及 JSON 解码。

内存采样需要 ADB shell 已有 root 权限，仅只读 `/proc`。
采样报告包含 PID、UID、进程名、RSS、VmHWM、PSS（KiB），约每秒一次。
普通和 root 模式均由普通扫描服务持有数据库；root 模式可另观察 UID 0 producer。
root 的应用授权须预先通过管理器授予，脚本不会修改管理器全局策略。

日志、JSON 指标及内存采样保存在忽略提交的 `.local/phase5/`。
规模报告的顶层 `performance` 来自实时终态，包含 journal 发布开销；历史 `metrics` 仍来自 SQLite。计时口径见[扫描性能测量契约](../../docs/scan-performance-measurement-contract.md)。
规模样本使用随机私有目录与 marker；测试 finally 删除本次样本。
如果测试进程被强制停止，marker 样本可能保留，重试前仅清理已核对 marker 的本次目录。
数据库保留扫描会话和快照索引，供后续目录 UI 开发；索引保留/删除界面属于阶段 8。
已有普通/root 基线目录不被修改或删除。

原生增量回归可复用阶段 2 的 CMake 构建目录，推送并以 root 运行
`disksize_stream_tests`；另有 `disksize_native_tests` 及 `disksize_root_pipe_tests`。
流式测试覆盖原始名称、2 万文件、取消、资源限制、消费者失败、目录替换、深度 FD 生命周期和同设备 bind mount。
