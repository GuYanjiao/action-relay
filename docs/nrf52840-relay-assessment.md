# nRF52840 Pro Micro 中转可行性

研究日期：2026-10-28。用户设备描述为“红板 Pro Micro”，先按 Pro Micro/SuperMini nRF52840 家族分析；尚未核对实物 PCB、引脚、Bootloader 和充电电路，未刷写、编译或进行无线互通试验。

## 结论

**nRF52840 具备实现 Action 4 BLE 控制器的硬件条件，但目前不能单板直接接替现有手机、保留 S4 快应用遥控。** 相机一段有官方协议；S4 的已知配套通道使用经典蓝牙 SPP，而该芯片只支持 BLE。没有证据表明目标国行 S4 3.201.79 还能通过一个可用的 BLE 应用消息通道完成同样工作。

这不是 MCU 运算速度不够，也不代表 S4 硬件绝对没有其他通道；它说明当前已验证的协议路线与所购芯片的无线制式不匹配。开发板连接相机的成功也不能证明完整手表中转成功。

| 环节或能力 | 判断 | 实现内容或限制 |
|---|---|---|
| 板 → Action 4 | 有实现依据，未实测 | BLE Central、服务发现、通知、分包写入、R SDK 授权及状态订阅。 |
| 录像、拍照及遥测 | 可移植协议逻辑，未实测 | 启停、拍照、状态/电量/时长/温度枚举；沿用现有确认和超时规则。 |
| S4 RPK → 板 | 目前缺少可用路径 | 现有 system.interconnect 不是任意 BLE UART，板不能运行 Android XMS 服务。 |
| 板模拟已知 S4 companion | 单板不具备对应无线制式 | 已知实现是 BR/EDR SPP，还需小米配对、认证、加密及应用消息协议。 |
| 直接安装现有 APK | 不可行 | MCU 不能运行 Android 应用；须实现 C/C++/嵌入式固件，而非安装 APK。 |
| 板端 GPS | 不能沿用手机定位 | nRF52840 没有内置 GNSS；需要外接 GNSS，或另行验证手表定位数据与传输路径。 |
| 电池与续航 | 待板型确认和测量 | 不按芯片典型功耗推算整板续航；红板克隆硬件可能不同。 |

## 一手依据

### 1. 开发板及无线能力

[Zephyr Pro Micro nRF52840 文档](https://docs.zephyrproject.org/latest/boards/others/promicro_nrf52840/doc/index.html)将它视为板型家族，列出 1 MiB Flash、256 KiB RAM 及 BLE 无线外设，当前有 `promicro_nrf52840/nrf52840` 构建目标。该页也提示没有活跃维护者，不能把通用板定义当作任意红板刷写保证。

[Nordic nRF52840 产品资料](https://www.nordicsemi.com/Products/nRF52840)列有 BLE、多角色等能力。[Nordic 技术支持答复](https://devzone.nordicsemi.com/f/nordic-q-a/90071/is-nrf52840-bluetooth-classic-compatible)明确 nRF52840 不支持 Bluetooth Classic / BR/EDR；同时作为 BLE Central 和 Peripheral 不会增加经典蓝牙能力。该答复的历史性全产品措辞不用于推断其他新型号，本判断仅针对 nRF52840。

### 2. S4 的实际配套链路

查阅 Gadgetbridge 官方源码，固定提交 `86703c8006fc701a81505a28c48e399d1428e0c1`：

- [XiaomiWatchS4Coordinator.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/86703c8006fc701a81505a28c48e399d1428e0c1/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/devices/xiaomi/watches/XiaomiWatchS4Coordinator.java)：`getConnectionType()` 返回 `ConnectionType.BT_CLASSIC`。
- [XiaomiSppSupport.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/86703c8006fc701a81505a28c48e399d1428e0c1/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiSppSupport.java)：连接串口服务、协商 SPP 版本、启动加密握手。
- [XiaomiUuids.java](https://codeberg.org/Freeyourgadget/Gadgetbridge/src/commit/86703c8006fc701a81505a28c48e399d1428e0c1/app/src/main/java/nodomain/freeyourgadget/gadgetbridge/service/devices/xiaomi/XiaomiUuids.java)：SPP 使用标准服务 UUID 0x1101。

上述代码证明的是一条已实现的开源路线，不证明 S4 所有固件只可能使用经典蓝牙，也没有证明 Gadgetbridge 已实现本项目的全部快应用消息。没有复制其代码到本项目。

小米公开 [Bluetooth API 支持表](https://iot.mi.com/vela/quickapp/en/features/system/bluetooth.html#support-details)仍将 S4 标为不支持，S5 为支持。项目先前实机诊断也没有扫描/GATT 入口；换成开发板不会自动开放手表应用权限。

现有项目 `XiaomiWatchBridge.java` 调用 Android XMS Wearable SDK，手表 `relayController.js` 使用 [system.interconnect](https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html)。[AstroBox Interconnect](https://abox.run/docs/plugin-development/v2/host-api/interconnect)提供按设备地址及包名收发快应用字符串的宿主接口，但这个接口不是可直接烧入 nRF52840 的固件，也不说明目标 S4 的底层通道必然是 BLE。

### 3. DJI 一段能复用什么

[DJI 官方 Osmo GPS Controller Demo](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo)公开 C 协议和 BLE 控制示例。当前仓库 `DjiProtocol.java` 已独立实现帧、CRC、Action 4 设备标识、授权、订阅、拍录和模式命令；`DjiSession.java` 将时钟、传输、事件分离。

适合复用的是协议和状态机设计、黄金帧与行为测试；Android BLE、XMS、前台服务与手机 GNSS 不能直接搬到 MCU。可用 Zephyr/nRF Connect SDK 的 BLE Central 实现相机传输；需保留稳定控制器身份、相机授权、写队列和状态确认，不能把 GATT 连接成功等同于可录制。

## 可选路线与验证顺序

1. **先用现有板验证相机端。** 核对实物和 Bootloader，优先 USB 供电；先扫描/连接/授权/状态订阅，再测试按键启停与断线。先不增加 GPS。验收依据是相机实际录制和保存，非仅串口打印成功。这个阶段可以得到一个独立相机按键遥控器，但还不是 S4 中转。
2. **若坚持 S4 无手机操作，先做手表消息回环。** 需要具备经典蓝牙的主机/模块，或找到并实测另一条 S4 BLE 应用通道。第一目标仅为 S4 快应用发送一个请求 ID，宿主收到并原样返回；此时还不接相机。认证凭据、互联封包及是否影响小米运动健康连接都需验证。
3. **两段分别验证后再合并。** 做手表指令 → 板 → 相机、状态返回与停止确认；最后才评估功耗、携带方式和 GNSS。

若后续考虑替换主控，[Espressif 蓝牙能力表](https://docs.espressif.com/projects/esp-idf/en/v6.1/esp32/api-guides/bt-architecture/overview.html)确认原版 ESP32 支持 Classic + BLE，而 ESP32-S3/C3 等不支持 Classic。这只是无线硬件条件，不是 S4 协议可直接移植或现成固件存在的保证。也可研究 nRF52840 加经典蓝牙串口模块，但必须核实模块能否以所需角色连接 SPP 并传输协议，不能仅凭“串口蓝牙”名称推荐某个商品。

本次未要求购买额外硬件；应先证明手表消息链路，再确定完整中转的硬件方案。
