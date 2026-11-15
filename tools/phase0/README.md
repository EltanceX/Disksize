# 阶段 0 环境验证工具

这些工具用于验证工具链和准备受控测试数据，不包含正式应用或扫描核心。

## PowerShell 环境

在仓库根目录执行，通过参数传入本机路径：

```powershell
. ./tools/enter-android-env.ps1 -FlutterSdk '<Flutter SDK>' -AndroidSdk '<Android SDK>' -Jdk '<JDK>'
```

脚本仅设置当前 PowerShell 进程的环境变量和 PATH，不写系统 PATH，不修改 Flutter SDK 源码。本机路径可放在 Git 忽略的 `.local/android-env.ps1` 中。

## 编译原生环境探针

探针输出 UID、页大小和可选目标的 `lstat`/`statfs` 信息，不遍历目录。

```powershell
$cmake = Join-Path $env:ANDROID_HOME 'cmake/3.22.1/bin/cmake.exe'
$ninja = Join-Path $env:ANDROID_HOME 'cmake/3.22.1/bin/ninja.exe'
$toolchain = Join-Path $env:ANDROID_HOME 'ndk/28.2.13676358/build/cmake/android.toolchain.cmake'

foreach ($abi in @('arm64-v8a', 'x86_64')) {
    & $cmake -S tools/phase0/native -B ".local/phase0/native-$abi" -G Ninja "-DCMAKE_MAKE_PROGRAM=$ninja" "-DCMAKE_TOOLCHAIN_FILE=$toolchain" "-DANDROID_ABI=$abi" -DANDROID_PLATFORM=android-29 -DANDROID_STL=c++_static -DCMAKE_BUILD_TYPE=Release
    if ($LASTEXITCODE -ne 0) { throw "CMake configuration failed: $abi" }
    & $cmake --build ".local/phase0/native-$abi"
    if ($LASTEXITCODE -ne 0) { throw "Native build failed: $abi" }
}
```

对选定的 arm64 设备执行，`$device` 应设为 `adb devices -l` 中确认的目标：

```powershell
adb -s $device push .local/phase0/native-arm64-v8a/disksize_phase0_probe /data/local/tmp/disksize-phase0-probe
adb -s $device shell chmod 755 /data/local/tmp/disksize-phase0-probe
adb -s $device shell /data/local/tmp/disksize-phase0-probe
adb -s $device shell su -c /data/local/tmp/disksize-phase0-probe
```

## 创建测试数据

脚本只在两个固定父目录下创建全新的运行目录：

- `/data/local/tmp/disksize-phase0/<run-id>`：原生文件系统的硬链接、稀疏文件、符号链接循环、权限拒绝、特殊名称、24 层深目录和 128 个小文件。
- `/sdcard/Download/DiskSize-phase0/<run-id>`：共享存储 FUSE 视图的基础样例。

需要已授权 root。脚本拒绝覆盖既有运行目录；不会自动删除数据。运行 ID 仅接受字母、数字、下划线和连字符。数据包含一个逻辑大小为 1 GiB 的稀疏文件，实际分配量应通过 `stat` 确认。

```powershell
adb -s $device push tools/phase0/create-fixtures.sh /data/local/tmp/disksize-phase0-create-fixtures.sh
# 每次复现选用尚未存在的运行 ID。
adb -s $device shell su -c 'sh /data/local/tmp/disksize-phase0-create-fixtures.sh example-run-01'
```

普通 ADB shell 受 SELinux 限制，可能不能创建硬链接，因此数据创建要求 root；验证时分别使用 shell 与 root 读取。ADB shell UID 2000 的访问能力不等于普通应用 UID，应用权限验证在正式服务接入后单独进行。

固定预期值如下，均不把目录与符号链接的 `st_size` 计入普通文件逻辑总量：

| 指标 | 原生样例 | 共享存储样例 |
| --- | --- | --- |
| 普通文件目录项 | 136 | 2 |
| 硬链接去重后普通文件 | 135 | 2 |
| 目录，包含根目录 | 31 | 2 |
| 符号链接 | 2 | 0 |
| 去重后普通文件逻辑字节 | 1,073,746,077 | 4,105 |

目录和小文件实际占用可能随文件系统变化，不将其分配量硬编码成跨设备断言。`du -b` 还会计算目录与符号链接的自身大小，其结果不能直接作为上表逻辑总量的预期值。

原始日志、交叉编译输出和临时 Flutter 工程保存在忽略提交的 `.local/phase0/`；验收摘要记录到 `docs/environment-baseline.md`。
