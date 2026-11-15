# 阶段 6 界面验证

在仓库根目录加载 `.local/android-env.ps1` 后执行：

```powershell
flutter analyze
flutter test
./tools/phase6/verify.ps1 -Serial '<device-id>'
# 本机已授权 ADB shell su 时，恢复正常 APK 优先使用 root 安装：
./tools/phase6/verify.ps1 -Serial '<device-id>' -RootInstall
```

脚本要求 PowerShell 7.4+（原生 PNG 二进制重定向）、Flutter、ANDROID_HOME 及已允许 ADB 调试/安装的设备。先构建并保存正常入口的双 ABI debug APK，再运行真实 Flutter → Pigeon → Binder → SQLite 集成测试。它在应用私有目录创建 55 个文件和一个带换行名称的嵌套文件，核验卷采样、最近会话、范围查询、祖先链、上下页、排序、目录下钻、详情和路径复制。

测试截图通过显式的 `PHASE6_CAPTURE` 编译参数启用，在 Flutter 移除测试安装前提取；日志、PNG 和正常入口 APK 位于忽略提交的 `.local/phase6/`。脚本结束时重新安装并启动保存的正常入口 APK。Root 管理器的授权按普通开发包 UID 管理，测试重装后可能需要重新授权；不会自动修改全局 root 策略。

本机后续安装优先调用 `tools/install-android-apk.ps1 -Serial '<device-id>' -Apk '<apk-path>' -UseRoot`，通过已授权的 `su pm install -r` 避免系统安装器确认。安装暂存到唯一的 `/data/local/tmp/disksize-install-*.apk`，结束即清理；未授权 root 的设备省略 `-UseRoot`，继续使用普通 ADB 安装。阶段 4 的 root 回归恢复步骤也复用此入口。安装失败会明确报错，不再把启动失败的空安装当作恢复成功。

不需要截图时可直接运行：

```powershell
flutter test integration_test/explorer_test.dart -d '<device-id>'
```

独立索引测试继续使用阶段 5 的 instrumentation 工具，本阶段扩充了真实 schema 1/2 升级、前后游标、子树范围、祖先链、共享链接和未知状态验证。旧 schema fixture 固定在 `android/app/src/androidTest/assets/index-v2.sql`，避免用新 schema 伪造旧数据库。

手机 360×800、双倍字号和 1280×900 分栏布局由 Flutter widget 测试覆盖。真实 root 扫描、原始字节及服务死亡回归沿用 `tools/phase4/verify.ps1`；它的授权驱动只适用已记录的本机 KernelSU 平板布局。
