# 0.4.3 云端验收

2026-10-27；应用代码提交 `0a8ba35b0ed74849865ce527ef2a48fad2738bbd`。Android / Watch 均为 **0.4.3 / versionCode 12**。

## 改动

- 9 点方向显示 SD 图标与剩余可录小时、分钟；3 点方向显示温度计与相机温度状态，左右镜像排列，避开录制按钮。
- 六个主界面状态图标共用 44×44px 样式，包含上方四个连接/GPS/电池图标、SD 和温度计；保持纯黑背景。
- DJI 1D02 偏移 30 的 `temp_over` 通过现有手机状态快照传给手表，无额外轮询。0 正常（绿）、1 偏高（黄）、2 过热禁录（红）、3 即将关机（红）；不显示为摄氏度。缺失、未知值、断线和过期显示灰色图标与 `—`。
- 本轮没有更改相机控制状态机，不因显示告警擅自发送停止或关机命令。

## 协议证据

重新通过 GitHub 官方 API 获取[大疆协议文档](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo/blob/main/docs/protocol_data_segment_CN.md)，文件 blob 为 `f4c0e19b6b71df784a153f755df204235cf7cb7b`。该文档定义温度等级，没有实际摄氏温度回传字段。

## 验证

- [Validate](https://github.com/GuYanjiao/action-relay/actions/runs/40360146370)：成功。
- [Build packages](https://github.com/GuYanjiao/action-relay/actions/runs/40360146595)：成功；APK/RPK 编译、固定签名及两端配对检查全部通过。
- 云端通过 48 项相机字段解码、19 个控制器场景、10 个长按场景、四档温度/异常输入格式及真实编译页面检查。左右对称、圆屏边界、与录制键的间距及六图标共用尺寸检查通过；既有协议、停止状态、GPS、队列/重连及签名回归全部通过。
- 本地仅编辑、静态语法和 JSON/XML 检查；没有本地编译或执行回归。

| 包 | SHA-256 |
|---|---|
| APK | 3fcea17cda12f60bf52e8db0df6c3628fbf26ec172c989c4258eee789db065db |
| RPK | 13bb3549c09eb283c02dd0805eb8acfcefadd633c003e39dae129408df22264d |

后续文档提交不改变上述已验收应用代码。

## 真机待验

Action 4 实际回报温度字段；S4 9/3 点图文可读性、无遮挡与图标视觉尺寸；断线后不得保留旧的“正常”温度状态。模拟和几何测试不替代真机验收。
