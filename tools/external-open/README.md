# 其他应用打开验证

先加载本机 Android 环境，再运行：

```powershell
. ./.local/android-env.ps1
flutter analyze
flutter test
./tools/external-open/verify.ps1 -Serial '<device-id>' -RootInstall
./tools/lint-android.ps1
```

`-RootInstall` 仅用于已授权 ADB shell root 的设备覆盖安装，不改变应用的运行 UID。脚本不卸载应用，保留已有扫描与快照，结束后恢复正常入口的双 ABI debug APK。普通设备可省略该参数。

profile 测试在应用自己的目录中创建独立样本，真实扫描后分别选中目录和文件，点击“使用其他应用打开”。主机轮询前台 `ChooserActivity`，记录实际应用选择器后按返回；Flutter 断言选中项和滚动偏移保留，最后仅删除本次测试的样本与快照。

输出保存在忽略提交的 `.local/external-open/`：`report.json`、`drive.log`、两份 chooser activity 记录、`external-open.png` 和正常入口 APK。脚本附着已安装应用的 Dart VM，不使用会卸载目标包的设备测试命令。

原生测试可独立运行（先安装正常 debug APK 和测试 APK）：

```powershell
./android/gradlew.bat -p android :app:assembleDebug :app:assembleDebugAndroidTest
./tools/install-android-apk.ps1 -Serial '<device-id>' -Apk build/app/outputs/apk/debug/app-debug.apk -UseRoot
./tools/install-android-apk.ps1 -Serial '<device-id>' -Apk build/app/outputs/apk/androidTest/debug/app-debug-androidTest.apk -UseRoot
adb -s '<device-id>' shell am instrument -w -r -e class com.example.disksize.open.ExternalOpenTest com.example.disksize.test/androidx.test.runner.AndroidJUnitRunner
```

应看到 `OK (6 tests)`，不能只依据 adb 命令退出码判断。测试要求设备安装兼容的系统 Files/DocumentsUI。独立测试 Activity 在测试 APK 的普通 UID 下读取授权 URI；通过测试驱动启动，避免设备厂商跨应用启动提示影响权限断言。样本位于应用缓存目录，完成后清理，不访问用户文件。

功能范围与权限说明见[使用其他应用打开](../../docs/external-open.md)。
