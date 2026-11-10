# 架构说明

## 界面与播放

`MainActivity` 管理本地 / SMB 两个主页入口及右上角设置、来源管理、目录、遥控器焦点、播放浮层和生命周期；`HomeDashboard` 实现受尺寸约束的入口布局。`PlaybackPreferences` / `PlayerDefaults` 区分全局默认和当前播放状态；`SourceCredentials` 通过 Android Keystore 的 AES-GCM 密钥保护持久化 SMB 密码。

LibVLC 3.6.5 负责容器解复用、视频 / 音频解码、字幕、轨道和输出。项目通过 LibVLC API 使用引擎，没有复制官方 VLC Android 客户端的界面代码。

## SMB 数据路径

```text
SMB 服务 → jCIFS-NG 只读流 → ForwardCache / PlaybackByteCache
                                       ↓
                              SmbPlaybackServer
                              回环 HTTP Range
                                       ↓
                                    LibVLC
```

缓存通道的 HTTP 服务器绑定回环地址、每次播放使用随机路径。`RangeReadAhead` 管理有界并发读；`ForwardCache` 在播放器停止索取数据时仍能填充目标窗口，前台优先且复用在途块。`PlaybackByteCache` 是有界磁盘 LRU，优先保护播放窗口；缓存上限不是预分配的 Java 内存。

MP4 / Matroska / fragmented MP4 的索引支持由相应解析器实现。时间覆盖和字节跳转支持程度各不相同；索引不支持的媒体按平均码率估算预读预算。跳转通过代际标识丢弃过时写入；流停止会关闭读者、任务和临时缓存。

兼容性设置提供原生 SMB 与串行路径，主动缓存不可用时保留顺序读取回退。`DownloadRate` 从实际远程读取字节计算滚动速率，磁盘命中不计入网络下载。

## 画面停滞恢复

`VideoRecovery` 是独立于 Android 的状态机，监视播放时钟和画面输出计数，支持没有 Buffering 回调时检测冻结。`MainActivity` 执行临时音频抑制、重同步和有上限的解码器重建，保留用户暂停意图与轨道。健康出帧后恢复音频；UI 只展示缓冲与速率，不展示内部恢复步骤。

部分固件不提供显示帧统计，只能降级参考解码统计。该检测无法保证识别所有电视驱动或显示层故障，需在具体电视上回归。
