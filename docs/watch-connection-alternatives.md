# 手表连接替代方案评估

> 2026-10-28 第二轮：已找到明确针对 S4 的嵌入式 BLE 配套示例；Gadgetbridge 的 SPP 选择不代表 S4 只能走 SPP。优先依据 [S4 BLE 补充调查](s4-nrf52840-ble-followup.md) 验证单板方案，下文历史“唯一可行”等措辞不能当作绝对限制。

> 2026-10-28 补充：本文早先将配套链路笼统称作 BLE 不适用于 S4 的全部实现。Gadgetbridge 的 S4 适配使用 BT_CLASSIC/SPP，不能据本文推断仅有 BLE 的 MCU 可取代手机。见 [nRF52840 评估](nrf52840-relay-assessment.md)。

评估日期：2026-10-26。对象：Xiaomi Watch S4（大陆版 3.201.79）→ Android 手机（小米 17 / Android 17）之间的蓝牙连接，回答"能否不每次打开小米运动健康就连手表"。结论已落地为手机端改动，见文末"落地改动"。

## 结论

没有能绕开小米运动健康、又保住现有 XMS 消息通道的直接蓝牙方案。手机端继续使用官方 XMS Wearable SDK 1.4；真正的摩擦来自小米运动健康后台被杀导致手表断连，属于**连接保活**问题，而非**连接方式**问题。因此采用"降摩擦"而非"换连接"：自动触发一次性通信授权、自动重连，并把防断连设置内置进 App。

## 现状与根因

手机端 `XiaomiWatchBridge` 绑定 `com.mi.health` / `com.xiaomi.wearable` 中的 `XMS_WEARABLE_SERVICE`：手表必须先由小米运动健康保持 BLE 连接，SDK 才能通过 `getConnectedNodes()` 看到它，再经 `AuthApi` 申请 `DEVICE_MANAGER` 权限、`MessageApi` 收发 JSON。

`DEVICE_MANAGER` 授权是每个 App+手表**一次性**、由小米运动健康存储；"每次都要打开小米运动健康"的根因是其后台服务被 HyperOS 省电策略杀掉 → 手表 BLE 断连 → 必须重开 App 重连。官方与社区的解法一致是"三步防断连"：省电不受限、允许自启动、多任务后台锁定。

## 替代方案评估

| 方案 | 能否绕开小米运动健康 | 结论 |
|---|---|---|
| 官方 XMS SDK（现状） | 否，依赖它 | 保留，唯一可行 |
| AstroBox / AstroBox-NG | 能直连，需先提取 AuthKey | 会**取代**而非共存小米运动健康，等于重写私有 messaging；见 [AstroBox 迁移评估](astrobox-assessment.md) |
| Gadgetbridge | 能直连，需 AuthKey | S4 为社区实验级，需 root/ADB 抓密钥，用户反馈 S4 常配对失败，同样取代官方 App |
| miwatch-heartrate | 能读心率 | 只走标准心率 GATT 服务，不能收发应用消息 |

关键约束：手表同一时刻只维持一条到 companion 的 BLE 连接；所有第三方直连都要先拿到 AuthKey（root 读库、或从官方 App 日志抓 `authKey`/`encryptKey`），且一旦直连就脱离小米运动健康，现有 interconnect/XMS 消息通道随即失效。

## 落地改动

- `XiaomiWatchBridge`：检测到已连接节点但缺 `DEVICE_MANAGER` 权限时自动调用 `requestPermission`；每个节点连接周期只自动尝试一次，失败或拒绝后退回手动入口，避免重复弹授权框。
- `MainActivity`：工具页新增"保持手表连接"，内置三步防断连说明，并提供一键打开小米运动健康的系统设置页。
- 保留官方 XMS SDK，不引入 Gadgetbridge / AstroBox 直连。

## 来源

- 小米官方 interconnect 文档：<https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html>
- AstroBox-NG 仓库：<https://github.com/AstralSightStudios/AstroBox-NG>
- Gadgetbridge Huami/Xiaomi 配对说明：<https://gadgetbridge.org/basics/pairing/huami-xiaomi-server/>
- miwatch-heartrate：<https://github.com/lsx-2319/miwatch-heartrate>
- 小米手表第三方连接讨论：<https://www.bilibili.com/opus/1085607959666360343>
- 小米运动健康防断连设置：<https://www.xiaohongshu.com/discovery/item/68ba4384000000001d005f73>
