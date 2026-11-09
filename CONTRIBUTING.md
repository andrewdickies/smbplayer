# 参与开发

欢迎提交问题和 Pull Request。讨论、文档及代码说明均可使用中文或英文。

## 开发环境

- JDK 17、Android SDK Platform 35 / Build Tools 35.0.0；Gradle Wrapper 固定 8.10.2。
- Python 3.9+ 用于无需设备的检查。
- `ANDROID_HOME` 指向 SDK，也可在不提交的 `local.properties` 中指定 `sdk.dir`。

```sh
python3 scripts/check.py
./gradlew :app:assembleDebug :app:lintDebug
python3 scripts/verify_apk.py app/build/outputs/apk/debug/app-debug.apk
```

Windows 使用 `python` 和 `gradlew.bat`。不需要访问任何维护者的 NAS、设备或私有镜像。网络受限时可自行在本机配置 Gradle init script，不应修改项目公共仓库配置。

## 提交要求

每个 PR 聚焦一个问题，说明触发条件、修改后的行为与验证结果。播放 / 网络改动需要验证暂停、跳转、返回目录及失败清理；UI 改动需要验证方向键、焦点恢复和不同宽度。保持 32 位 ARM 支持，不引入只支持 64 位的库。

运行设备测试前阅读 [checks/README.md](checks/README.md)。测试会改变设备状态，只在自己的测试设备和授权媒体上运行。不要提交服务器地址、账号、密码、播放记录、日志中的私密 URL、APK 或签名密钥。报告问题时使用脱敏样例。

提交贡献即表示你有权以项目的 GPL-3.0-or-later 许可证提供这些改动。
