# S4 → nRF52840：BLE 路线补充调查

2026-10-28。目标仍是国行标准版 Xiaomi Watch S4、固件 3.201.79、红色 Pro Micro nRF52840。用户本轮明确确认手表有“心率广播／蓝牙心率共享”开关。没有刷写或无线实测，也未获取用户认证密钥。

## 更新结论

**不能再因 Gadgetbridge 为 S4 选择 SPP，就把 SPP 当作 S4 唯一通信方式。现在已找到专门针对 S4 的嵌入式 BLE 配套示例，nRF52840 单板方案值得优先验证。** 这不等于已经证明该示例兼容本机固件或能够直接移植到 nRF52840。

两条路线的目标不同：心率广播能验证 BLE 基础连接；接替 BLE companion 才有希望继续使用现有 RPK 录制按钮。不能把心率值变化编码成录制指令，也不能把收到心率视作应用消息互通成功。

## 路线一：标准 BLE 心率广播

- 用户已确认本机有该功能入口。
- [PulseBridge 作者 README](https://github.com/qixishunzi/PulseBridge/tree/ad482a46db6cf2d9d6f01181406064aa9f948499)明确声明在 Xiaomi Watch S4 测试成功。
- 同提交 [public/receiver.html](https://github.com/qixishunzi/PulseBridge/blob/ad482a46db6cf2d9d6f01181406064aa9f948499/public/receiver.html)使用标准心率服务 `0x180D`、测量特征 `0x2A37`，通过 GATT 通知收取心率。

可让手表作为心率外设、nRF52840 作为 BLE Central 接收心率，先验证扫描、连接、服务发现和通知。作者的实测不代表本项目已经在用户设备上通过；最终以本机广播及 GATT 结果为准。该通道没有显示可让 Action Relay 任意写入按钮消息的接口。

## 路线二：开发板接替 BLE 配套通道

### 针对 S4 的嵌入式源码

[AstroBox-NG-Module-App-ESP32S3/src/miwear.rs](https://github.com/AstralSightStudios/AstroBox-NG-Module-App-ESP32S3/blob/eee11923228624fd073787b2e92695d919b56ce1/src/miwear.rs)，固定提交 `eee11923228624fd073787b2e92695d919b56ce1`：

- 52 行目标名称明确为 `Xiaomi Watch S4`；此名称和 FE95 扫描条件不能代替目标设备身份校验。
- 使用 BLE Central 扫描、连接，不调用经典蓝牙 SPP。
- 服务 `FE95`；读取 `0050` 特征，订阅 `005E` 通知，向 `005F` 写入。必须记录实际完整 UUID、属性和 MTU，不能把源码的子串匹配规则当作通用稳定规范。
- 创建 Xiaomi 设备时显式选择 `ConnectType::BLE`，使用 SAR 版本参数和 AuthKey。
- 示例还初始化 [ANCS 兼容服务](https://github.com/AstralSightStudios/AstroBox-NG-Module-App-ESP32S3/blob/eee11923228624fd073787b2e92695d919b56ce1/src/miwear/ancs.rs)，包含 BLE 安全配对及服务端处理；是否是本机最低通信所必需，需要单独测试。
- 示例进一步请求快应用列表并尝试启动一个快应用，但源码路径存在不等于该操作在目标固件实测成功。

仓库名为 ESP32S3，README 标题又称 ESP32C3，存在命名不一致。关键证据是实际代码使用 `esp32_nimble`、S4 名称、FE95 特征和 BLE 类型，不是依靠仓库名推断。源码中自带的示例认证值不能当成用户设备密钥使用；此文不收录该值。

### 认证与应用消息源码

[AstroBox-NG-Module-Core](https://github.com/AstralSightStudios/AstroBox-NG-Module-Core/tree/dc08a079501b3ca7dd9215727ccbc348278914fc)，固定提交 `dc08a079501b3ca7dd9215727ccbc348278914fc`：

- [components/auth.rs](https://github.com/AstralSightStudios/AstroBox-NG-Module-Core/blob/dc08a079501b3ca7dd9215727ccbc348278914fc/src/device/xiaomi/components/auth.rs)：设备 AuthKey、随机挑战、HMAC 校验、会话密钥和 AES-CCM，另有 BLE companion 分支。
- [components/thirdparty_app.rs](https://github.com/AstralSightStudios/AstroBox-NG-Module-Core/blob/dc08a079501b3ca7dd9215727ccbc348278914fc/src/device/xiaomi/components/thirdparty_app.rs)：`send_phone_message`、应用连接状态同步、`handle_message_content` 和 `InterconnectMessage` 事件，具有双向快应用消息处理代码。发送入队和启动应用本身不提供应用级成功确认。
- [Bluetooth 模块 stdimp.rs](https://github.com/AstralSightStudios/AstroBox-NG-Module-Bluetooth/blob/6bf8b59c5a2d9dcdad2c978d07e16f53f3ae2aba/src/stdimp.rs)：包含实际 BLE 连接、特征发现、写入与订阅；不仅是之前文档中接近占位状态的插件 Transport 接口。

推论：若目标 S4 的 FE95 配套通道可用，且完成其认证和消息传输，原 RPK 的 `system.interconnect` 有机会继续使用，不必让 RPK 直接调用被限制的 `system.bluetooth.ble`。这是绕过“应用没有通用 GATT API”的架构路线，不是已经绕过或解除固件权限。

## nRF52840 所需最小实现

只移植通信所需部分：BLE 客户端、可能需要的配套 GATT 服务、认证、SAR 分包、最小 Protobuf 和快应用消息。完整 AstroBox 的 Rust std/Tokio/ECS/界面不是可直接烧入 256 KiB RAM MCU 的固件，需要重新裁剪和适配，不预先承诺占用能满足要求。

最终候选结构：

```text
S4 快应用 ─ system.interconnect ─ S4 系统
                                  │ BLE 配套协议
                               nRF52840
                                  │ BLE DJI R SDK
                                Action 4
```

开发板可同时保持两条 BLE 连接的硬件能力不等于已实现这两个应用协议；连接数、ATT MTU、队列与调度仍需配置验证。GAP 中心/外设角色与 GATT 客户端/服务器角色要分别设计。

## 建议按四个阶段验收

1. **不改变手机绑定关系，先枚举服务。** 在可连接状态用诊断端记录目标 S4 的广播与 GATT。心率广播检查 180D/2A37；另查 FE95 及 0050/005E/005F 的完整 UUID 和权限。绑定占用导致不广播时先记录现象，不直接恢复出厂或重置手表。
2. **只做认证。** 需要用户自身设备的合法配对凭据，不能从源码生成通用 AuthKey。测试单独 BLE 配套认证，核实是否需要 ANCS 兼容服务、手机需否临时断开、与小米运动健康是否可共存及如何恢复。当前均未实测。
3. **只做应用消息回环。** RPK 发送带 ID 的 `ping`，板原样回 `pong`；认证成功、消息收到与应用确认分开记录。通过此步才算具备中转上游通道。
4. **最后接 Action 4。** 移植现有 DJI 协议与启停确认，再测试两端同时连接、断线后不重发快门，以及可选的心率数据。GPS/ANT+ 暂不纳入最低可用链路。

本次只更新研究依据，没有复制上游代码、增加固件或执行编译；正式移植时需要遵守所采用代码的许可证及署名要求。也没有要求更换开发板、刷手表固件或获取他人密钥。
