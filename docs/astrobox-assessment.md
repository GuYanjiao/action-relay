# AstroBox 迁移评估

评估日期：2026-10-26。对象：[abox.run 官方文档](https://abox.run/)，结合当前 Xiaomi Watch S4 → Android 手机 → DJI Action 4 的项目结构。本次仅分析，没有迁移实现或真机互通证明。

## 结论

适合考虑增加一个可选的 AstroBox 通信/分发适配层；目前不建议把整个手机中转端改写成 AstroBox 插件。它对手表连接和生态分发有帮助，但我们最关键的 DJI BLE、完整手机 GNSS、前台服务和快捷磁贴，仍需要保留原生能力或新增宿主接口。

## 已确认的价值

- 官方支持范围包括 Xiaomi Watch S3 及后续系列，因此 S4 在其声明范围内；这只是平台支持声明，不是本项目与 S4 3.201.79 的互通实测。[支持列表](https://abox.run/)
- Interconnect 可以按设备地址和快应用包名发送字符串，并通过注册接收接口取得手表消息。现有 JSON 指令格式有机会复用，替换手机与手表之间的一段通信。[Interconnect 文档](https://abox.run/docs/plugin-development/v2/host-api/interconnect)
- 第三方快应用管理和启动接口有利于安装、调试与后续分发。手表仍运行 Vela 快应用，已有图形界面和控制器可以继续保留。[快应用接口](https://abox.run/docs/plugin-development/v2/host-api/thirdpartyapp)
- 插件生态提供跨平台宿主和 HTTP 等能力，适合探索与现有 Android 中转服务之间的可选桥接。[Level 4 概览](https://abox.run/docs/plugin-development/v4)

## 主要成本与未证明事项

| 现有能力 | 迁移判断 |
|---|---|
| 手表 UI | 不会因采用 AstroBox 自动改善；依旧需要维护 RPK 的 UX、图标、计时和控制逻辑。 |
| 手表消息 | 有公开的 Interconnect 路径，可做小规模兼容性验证。消息顺序、确认和重连可靠性仍需实测。 |
| Action 4 蓝牙 | 不能把 AstroBox 的穿戴设备传输接口当作通用相机 GATT。已核实的 Transport 文档描述的是 Xiaomi Vela 协议，并明确提醒实现状态有限；尚未核实到能直接替代现有 DjiBle 的插件接口。 |
| 手机 GPS | 安装指南所述位置权限与蓝牙连接有关，不能据此推断插件可获得完整 GNSS 测量。公开接口尚不能证明能提供本项目所需的海拔、速度、精度、卫星数等完整数据。 |
| 前台运行/磁贴 | Android 原生服务、权限与 TileService 不能仅通过改写消息格式迁入 WASI 插件；需要宿主支持或保留独立 APK。 |
| 开发维护 | Java Android 平台调用不能直接成为现成 WASI 插件。新增 Rust/JS/Python 插件或原生宿主改造会增加构建、版本和故障排查路径。 |

依据：[Transport 当前状态](https://abox.run/docs/plugin-development/v2/host-api/transport)、[Android 安装权限说明](https://abox.run/docs/usage/install)、[插件语言与工具链](https://abox.run/docs/plugin-development/v4/getstarted/language)、[官方 WIT 世界定义（含 Level 4）](https://github.com/AstralSightStudios/AstroBox-Plugin-WIT/blob/main/main.wit)。以上“尚未核实”不表示技术上绝对不可能。

如果选择直接改造 AstroBox 宿主，还要注意官方仓库明确列有私有 App/WebUI 等模块，不能假定完整产品可仅靠公开源码复建；许可证和署名要求也需要单独核对。[仓库说明](https://github.com/AstralSightStudios/AstroBox-NG)、[模块可见性清单](https://github.com/AstralSightStudios/AstroBox-NG/blob/main/repos.xml)。

## 对当前项目的建议

1. 继续维护原生 Android 中转作为稳定主线，先完成 GPS 和停止回报的真机验收。
2. 若验证 AstroBox，先做仅消息往返的独立实验：S4 发一个带请求 ID 的消息，插件收到后回传相同 ID；不立即接管录像。
3. 再考虑“手表 RPK → AstroBox 插件 → 本机 Android 中转服务 → Action 4”的混合适配。它可以保留现有相机协议/GNSS，但会新增一跳通信和宿主依赖，不能预先承诺延迟或耗电更低。
4. 只有实测确认后台、断线恢复、消息确认、定位数据和双设备连接都稳定，才判断能否进一步减少独立 APK。若走本机 HTTP 桥，需要限定回环地址并鉴别会话，不能开放无认证录像控制接口。

当前收益主要是生态兼容和分发便利；对当前单一 S4 + Action 4 的录制/GPS 需求，整体重写的成本与未知项更大。
