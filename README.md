# Action Relay

Action Relay 是一个 Android 手机中转应用和 Xiaomi Vela 手表端应用：用
Xiaomi Watch S4 控制 DJI Osmo Action 4 开始或停止录像，并由手机采集真实
GNSS 数据后发送给相机。当前版本为 **0.4.3（versionCode 12）**。

## 工作方式

```text
Xiaomi Watch S4
        │ Xiaomi 设备互联
        ▼
Android 手机（Action Relay）
        │ Bluetooth LE / DJI R SDK 协议
        ▼
DJI Osmo Action 4
```

手机负责与相机建立 BLE 会话、发送录像控制和 GPS 数据，也负责把相机状态
转发给手表。手表只呈现控制入口和短状态，不直接操作相机。开始和停止是
明确的独立指令；停止等待相机确认，未知或过渡状态不会被当作“已停止”。

## 功能与支持范围

- 手表端提供大号图形化录制控制、计时、相机/手机连接状态和 GPS 状态。
- 手表端支持可关闭的录制反馈：开始录像短振，按下停止键进入长按时立即长振。
- 手机端提供相机搜索、配对、有限重连、状态显示和系统明暗主题适配。
- 手机快捷设置磁贴用于一键开启中转，不控制录像；已开启时重复点击不关闭中转。
- 停止长按采用原生连续进度环：提前松手回落，发出停止请求后松手立即隐藏。
- 手表状态图标沿表圈内侧圆弧排列；9 点方向用 SD 图标显示相机回报的剩余可录时长，转换为小时、分钟。3 点方向显示相机温度状态（正常、偏高、过热禁录、即将关机，非摄氏度），主界面六个状态图标统一尺寸。底部仅保留只读分辨率/帧率；断线时主键变为刷新，点击顶部状态图标进入设置。
- GPS 使用手机真实 GNSS，区分权限不足、定位关闭、等待首次定位、测量不
  完整、数据过期和启动失败，不伪造缺失字段。
- 目前面向国行 Xiaomi Watch S4（3.201.79）与 DJI Osmo Action 4；手机需
  Android 12 或以上，并安装、连接小米运动健康。其他组合尚未完成兼容性验证。
- 云端构建和自动回归不等于硬件证明。相机停止/保存、手机与手表通信、GNSS
  质量以及视频中的 GPS 数据仍需分别在实机上验证。

手机界面采用接近 HyperOS 的卡片与图形化控件，随系统切换明暗模式；手表
界面使用适合 OLED 的纯黑背景。Xiaomi Vela 官方公开接口目前没有目标
Watch S4 可用的声音播放接口，因此 0.4.0 只实现官方支持的振动反馈，不宣称
有声音反馈。

## 下载与安装

安装包只从同一次 GitHub Actions 构建获取，以保证 APK 和 RPK 配套：

1. 打开 [Build packages 工作流](https://github.com/GuYanjiao/action-relay/actions/workflows/build.yml)，
   选择 `main` 上成功的运行。
2. 在 **Artifacts** 下载 `action-relay-版本-提交号`，解压得到 APK、RPK
   和公开的签名报告。
3. 在 Android 手机上安装 APK；使用现有 Xiaomi Vela 快应用安装方式安装
   RPK。首次运行时授予附近设备和通知权限；GPS 功能另需精确位置权限。
4. 在手机端开启中转并完成手表通信授权，再搜索并配对 Action 4。需要 GPS
   时打开系统定位，在开阔环境等待真实测量。

安装过程中请使用同一构建的两端包。首次从旧测试包切换到固定发布证书时，
可能需要先卸载旧包后重新授权和配对；卸载手机应用会清除本应用保存的配对
记忆。GitHub Actions 附件默认保留 30 天，过期后可手动触发构建。

## 开发

项目由 Android 原生 Java 手机端和 Vela JavaScript/UX 手表端组成。本地只做
编辑和静态检查，不在本机编译 APK、RPK 或 Java；构建、回归、签名和包配对
检查由 GitHub Actions 执行。开发入口、工具链、消息契约和测试目录见
[开发文档](docs/development.md)。

相关入口：

- [GitHub Actions 工作流](.github/workflows)
- [Copilot 工作流](docs/copilot-workflow.md)
- [第三方说明](THIRD_PARTY_NOTICES.txt)

手表系统小组件的公开接入限制见[可行性核对](docs/watch-widget-assessment.md)，相机可扩展信息见[大疆协议核对](docs/dji-watch-telemetry.md)。
