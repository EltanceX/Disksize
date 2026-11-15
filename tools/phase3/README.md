# 阶段 3 验证工具

先在仓库根目录加载环境：

```powershell
. ./.local/android-env.ps1
flutter analyze
flutter test
./tools/lint-android.ps1
```

普通应用 UID 的基本设备集成测试：

```powershell
flutter test integration_test/scanning_test.dart -d '<device-id>'
```

覆盖自身目录真实扫描、5 GiB 稀疏文件、Unicode/换行路径、权限拒绝和 Partial、失败、取消、页面销毁、真实工作进程 SIGKILL、Interrupted 恢复、已完成预览的持久恢复。样本均在应用私有目录中新建并清理，不需要 root。测试 APK 可能被 Flutter 测试工具卸载；手动运行后可重新构建安装正常入口。

完整生命周期脚本面向当前 Android 15+ 测试设备，需要 ADB 调试权限：

```powershell
./tools/phase3/verify.ps1 -Serial '<device-id>'
./tools/phase3/verify-revocation.ps1 -Serial '<device-id>'
```

`verify.ps1` 先构建双 ABI 正常入口 APK 并安装，保存原来的所有文件访问 AppOp 和 dataSync 超时参数，再运行设备测试。它临时授予测试应用所有文件访问，在收到测试标记时执行真实 HOME/返回切换，并把 `data_sync_fgs_timeout_duration` 临时缩短到 250 ms 以触发系统 `onTimeout`。最后恢复设置、正常 APK 和首页。系统可能在新任务开始时立即执行已经耗尽配额的超时回调；测试同时覆盖这种情况。

`verify-revocation.ps1` 在正常应用中用 UI Automator 观察控件，再通过 ADB 输入选择“自定义真实路径”和新建的受控共享目录，启动真实扫描。撤销所有文件访问后重新打开应用，断言原会话为 Interrupted 且不完整，并恢复 AppOp。部分设备会在撤销 AppOp 时直接终止 UI 和服务进程，因此此项由主机执行，避免同进程测试运行器被一起终止。

撤销测试需要已经安装本阶段的可调试正常 APK、设备已解锁且未被其他窗口覆盖，应用界面使用当前中文文案。脚本只清理自己通过 mktemp 创建、且绝对路径前缀和名称已核对的 `Download/disksize-phase3-revoke-*` 样本。集成测试另使用 Dart 新建的 `disksize-phase3-test-*` 样本并在 finally 清理。中途手动终止主机脚本可能阻止 finally 执行，应查看 `.local/phase3/device-settings-before.json` 和日志，恢复其中记录的原值并清理本次确切样本。

日志、正常 APK、UI XML、撤销前后会话摘要均保存在忽略提交的 `.local/phase3/`。所有扫描执行在普通应用 UID 下；这些工具不需要 `su`，也不修改 SELinux。API 29/30～34 的实际系统分支应在对应设备上另做运行验证；本脚本的缩短 dataSync 超时检查仅适用于支持该系统机制的版本。
