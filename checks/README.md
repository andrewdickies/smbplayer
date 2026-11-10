# 测试与诊断

这些工具只用于开发，不会自动打包到 APK，也不会自动连接任何 NAS。

## 无设备检查（CI 默认）

JDK 17、Python 3.9+：

```sh
python3 scripts/check.py
```

运行缓存、索引、并发读取、冻帧恢复状态机、网速、跳转策略、文件显示和排序共 10 组检查。测试创建自己的临时目录并清理；不需要外网、媒体下载、ADB 或账号。Android 构建和 lint 单独运行 `./gradlew :app:assembleDebug :app:lintDebug`。

## 媒体 / SMB 手工检查

`ContainerBufferCheck`、`IndexedSeekCheck`、`FragmentDurationCheck` 需要自备媒体文件及预期时长。`SmbForwardServerCheck`、`SmbCacheServerCheck`、吞吐探针需要自建只读 SMB fixture 和对应的本地文件；阅读每个 main 方法的参数契约后运行。旧工具 `SmbReadAheadServerCheck` 针对历史预读实现，当前主动缓存回归使用 `SmbForwardServerCheck`。不要把空参数运行的媒体检查视为实际覆盖。

`rebuffer_http.py` 是仅绑定 `127.0.0.1` 的只读 HTTP 断流 fixture；通过 `python3 checks/rebuffer_http.py --help` 查看参数。不要把测试服务暴露到公网。

## Android 原生手工检查

`*Instrumentation.java` 是开发时的临时测试入口，依赖当前私有实现，不是稳定的公开测试 API。正常 source set 不包含它们。如需重现，把目标测试类临时复制到 `app/src/debug/java/com/smbplayer/checks/`，并在 `app/src/debug/AndroidManifest.xml` 中为对应类声明 `<instrumentation android:name="com.smbplayer.checks.类名" android:targetPackage="com.smbplayer.tv" />`。编译安装后使用 `adb shell am instrument -w ...`。测试结束必须删除临时 source set 并重新构建；`scripts/verify_apk.py` 会拒绝含测试类的 APK。

**仅使用专门测试设备。** 这些测试可能修改设置、选择来源、改变播放位置、制造渲染故障及写入截图；不会自动备份和还原真实用户数据。请先备份，手工准备媒体，最后还原设备。静音测试应先将系统媒体音量设为 0；部分测试会明确检查静音状态。

SMB 设备测试要求先在测试 App 中保存自己的 SMB 来源，通过 instrumentation 参数选择它：`-e smbHost nas.example.test -e smbPort 445 -e smbSubdir test-videos/`。只递归访问该相对子目录；参数不包含密码。账号密码通过 App 正常保存，不要写入测试代码。`BufferingInstrumentation` 还需要名为 `dashboard-fixture.mp4` 的应用私有媒体文件及至少一个大于 64 MiB 的可播 SMB 视频。

历史手工验证及未验证硬件能力见 [VALIDATION.md](../VALIDATION.md)。
