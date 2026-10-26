# Watch S4 系统小组件接入调查

核对日期：2026-10-26；目标为国行 S4 3.201.79。这里指从表盘旋转表冠进入的系统卡片列表，不是手机桌面 Widget、表盘或应用内组件。

## 当前判断

用户确认微信支付、支付宝具有该入口，说明系统能够承载第三方服务卡片。不能据此断言普通 RPK 自动具备注册权限，也不能再用“只有系统内置应用能做”解释。当前没有交付 Action Relay 系统卡片；尚缺新卡片的注册及分发证据。

小米 [S4 小组件说明](https://www.mi.com/ph/support/faq/details/KA-538734/)描述编辑、添加卡片，海外说明不能代替国行固件验证。公开 Vela [manifest](https://iot.mi.com/vela/quickapp/zh/guide/framework/manifest.html)、组件/API 目录及[手表应用发布说明](https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2058)尚未提供可核实的 S4 卡片注册示例。支付应用是否通过合作方原生接口、专用签名或额外资源注册，仍为待证假设。

## 找到的实际协议入口

Gadgetbridge 官方源码已经实现小米系统卡片配置，以下核对固定于提交 `ed37d5f8c66b63e8c60f473ad6575b71d41f1a87`，通过 Codeberg 官方 API 读取；没有复制其 AGPL 实现到本项目。

- [wear_system.proto](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/ed37d5f8c66b63e8c60f473ad6575b71d41f1a87/GBProtobufGenerator/src/main/proto/xiaomi/wear_system.proto)：系统命令类型 2；子命令 51 获取卡片页面，52 写入页面布局，53 获取可用卡片部件。`WidgetPart` 包含 type、function、id、title、subType、appId、unknown7；`appId` 的注释仅记录部分读取为空，不能推导任意包名都可用。
- [XiaomiSystemService.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/ed37d5f8c66b63e8c60f473ad6575b71d41f1a87/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/services/XiaomiSystemService.java)：初始化读取页面和部件列表；保存时发送已有页面结构，没有注册应用卡片的独立实现。
- [XiaomiWidgetManager.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/ed37d5f8c66b63e8c60f473ad6575b71d41f1a87/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/xiaomi/XiaomiWidgetManager.java)：`getSupportedWidgetParts` 从手表回报的列表选择；`saveScreen` 通过 type/id 找到已知部件并复制。可创建页面，不等于可创建新的渲染提供者。
- 同版本 [wear_thirdparty_app.proto](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/ed37d5f8c66b63e8c60f473ad6575b71d41f1a87/GBProtobufGenerator/src/main/proto/xiaomi/wear_thirdparty_app.proto)描述 RPK 列表、安装和删除，没有卡片注册字段。这只说明该开源实现尚未描述它，不能证明固件不存在其他通路。

Gadgetbridge [设备说明](https://freeyourgadget.codeberg.page/gadgetbridge_org/basics/topics/xiaomi-protobuf/)列有 Watch S4，同时声明具体功能依设备能力而异。上述源码是实际研究路径，不是当前国行 S4 或本项目卡片已经互通的证明。它使用手表配套通信协议；本项目现有 `@system.interconnect` 应用消息不能直接当作这些系统管理命令发送。

## 下一步需要区分的两种情况

1. 若 Action Relay 安装后已出现在部件列表，研究将转向布局配置和点击启动入口；优先使用系统/小米运动健康提供的管理入口，先备份原布局。
2. 若只有微信/支付宝等出现在列表，必须继续获取卡片提供者的注册方式：官方 SDK/合作接入规范，或可合法取得的第三方示例包及声明。对比安装前后部件列表与卡片布局，可以验证 `appId`、function/id 的真实关系；不猜测 ID、不覆盖支付卡片。

[Vela hap 链接](https://iot.mi.com/vela/quickapp/zh/guide/framework/other/hap-schema.html)可打开应用，但不能自行创建系统卡片。支付服务存在并不意味着应修改支付包或借用其身份。本轮没有改配对、刷固件、替换系统组件，也未发送小米管理协议写入命令。
