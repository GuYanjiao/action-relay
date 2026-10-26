# Action Relay

用 Xiaomi Watch S4 遥控 DJI Osmo Action 4。Action Relay 通过 Android 手机中转，让你在手腕上开始、停止录像，查看相机状态，并向相机发送手机 GPS 数据。

[下载安装包](https://github.com/GuYanjiao/action-relay/releases/latest) · [使用与开发文档](docs/development.md) · [第三方说明](THIRD_PARTY_NOTICES.txt)

## 功能

### 手腕上的相机遥控器

- **轻触开始、长按停止**：大号图形化录制键，停止时显示连续红色进度环；提前松手时进度回落。
- **录制状态与计时**：根据相机回报更新状态，区分命令发送、等待确认和实际录制，避免将通信成功误显示为停止成功。
- **相机信息一眼可见**：显示手机/相机连接、GPS 状态、电量、剩余可录时间，以及普通视频的分辨率和帧率。
- **温度提醒**：显示相机回报的正常、偏高、过热禁录、即将关机四档状态。
- **适合圆形 OLED 屏幕**：纯黑背景，顶部状态图标沿表圈排列，9 点方向显示剩余可录时间，3 点方向显示温度状态。
- **便捷操作**：断线时主键变为刷新；点击顶部状态图标进入设置，可开关录制振动反馈。

### 手机负责连接与 GPS

- 搜索、配对和记住相机，支持启动重连、有限重试和手动忘记设备。
- 通过前台中转服务连接手表与相机，优先处理录制控制，合并状态与 GPS 更新。
- 使用手机真实 GNSS 测量向相机发送 GPS 数据，显示权限、定位质量、数据过期等状态。
- 提供 **Action 中转** 快捷设置磁贴，一键开启中转服务。
- 界面跟随手机系统切换亮色、暗色模式。

## 工作方式

```text
Xiaomi Watch S4  ←→  Android 手机  ←→  DJI Osmo Action 4
                小米设备互联      Bluetooth LE / DJI R SDK
```

手机负责相机连接、录像命令和 GPS 数据；手表作为图形化遥控器使用，需要手机中转保持运行。

## 下载与使用

1. 在 [Releases](https://github.com/GuYanjiao/action-relay/releases/latest) 下载**同一版本**的手机端 `.apk` 和手表端 `.rpk`。
2. 在手机上安装 APK；通过支持 Xiaomi Vela 快应用的安装工具将 RPK 安装到手表。
3. 确保手表已连接小米运动健康。在手机端授予附近设备、通知和手表通信权限，开启中转并搜索、配对相机；按相机提示确认连接。
4. 打开手表端应用，连接就绪后轻触开始录像，长按约 650ms 停止。
5. 如需 GPS，授予精确位置权限并打开系统定位，在开阔环境等待有效测量。可将 **Action 中转** 磁贴加入手机快捷设置，方便下次启动。

升级时请同时更新两端。若旧测试包与发布包签名不同，需要先卸载旧包；卸载会清除应用保存的相机记忆和设置。Release 附有校验文件，开发构建也可从 [GitHub Actions](https://github.com/GuYanjiao/action-relay/actions/workflows/build.yml) 获取。

## 兼容性与功能边界

面向国行 **Xiaomi Watch S4（固件 3.201.79）**、**DJI Osmo Action 4** 开发；手机要求 **Android 12 及以上**，并连接小米运动健康。其他手表、相机和固件组合未作兼容保证。

- 分辨率、帧率目前只读，需在相机上修改。温度是过热等级，**不是摄氏度数值**。
- 剩余可录时间来自相机，按小时、分钟显示；字段缺失、过期或未知时显示 `—`。
- GPS 功能依赖手机定位、后台运行和相机支持；视频实际记录的 GPS 数据需按设备与固件验证。
- 当前不提供手表系统小组件入口。相关接入调查见[小组件文档](docs/watch-widget-assessment.md)。

## 开发与协议

手机端使用原生 Java，手表端使用 Xiaomi Vela JavaScript/UX。构建、回归测试和固定证书签名由 GitHub Actions 完成。

- [开发文档与消息契约](docs/development.md)
- [相机状态与协议字段](docs/dji-watch-telemetry.md)
- [构建工作流](.github/workflows)
- [DJI 官方 R SDK 示例](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo)
