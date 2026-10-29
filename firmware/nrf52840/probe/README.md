# nRF52840 阶段 1：S4 BLE 诊断

这是服务发现和心率通知固件，还不是相机中转固件。目标为 Pro Micro nRF52840，云端使用 Zephyr 4.5.0 的 `promicro_nrf52840/nrf52840/uf2` 板定义。

## 当前能力

USB 串口命令行；仅打印名称含 Watch S4、声明心率或 FE95 服务的广播；按用户指定地址连接；枚举全部服务、特征及其属性；订阅标准 180D/2A37 心率通知。

不自动配对、认证、重连或控制相机。未加入 AuthKey、ANCS、Xiaomi SAR/Protobuf 和应用消息，遇到加密访问要求会失败并保留错误码。

## 使用前

先确认启动盘的 `INFO_UF2.TXT` 中 Board-ID、Bootloader 和 SoftDevice 信息，确认与 Zephyr 上游 UF2 布局兼容后再刷写。本阶段不提供自动刷写脚本，也不要用全片擦除或替换 Bootloader 来试错。UF2 文件成功编译不证明它适配所有红色克隆板。

没有板卡 USB 信息时，先取云端产物和记录，不直接刷写。USB 正常运行时通过串口终端打开相应端口（115200，8N1），按回车看到 `s4>`。

## 诊断顺序

1. S4 开启心率广播，保持原有绑定，不重置手表。
2. `probe scan`：从目标手表名称及广播核实地址和 public/random 类型。FE95 也可能属于其他小米设备，不能只凭这个标记选择设备。
3. `probe connect AA:BB:CC:DD:EE:FF random`：使用自己手表的实际地址和类型；若日志标为 public，则替换最后参数。
4. 等待 CONNECTED 后执行 `probe services`，等 DISCOVERY done，再执行 `probe chars`。
5. 若有心率测量特征，执行 `probe hr`；收到 `HR bpm=...` 才证明这一条通知通道通过。
6. 记录是否存在完整 FE95、0050、005E、005F UUID 及属性，供配套认证阶段使用。仅枚举到特征不算认证通过。
7. `probe disconnect` 结束；`probe stop` 停止扫描。重试需手动执行，不自动重放。

串口日志可能含设备地址和心率，保留本地即可，不提交个人日志到仓库。

## 云端检查

独立工作流构建 UF2/HEX/ELF，并运行心率报文解析边界测试、输出 SHA-256 与构建元数据。没有本地编译、实机刷写或配对证明。既有 APK/RPK 版本不变。

下一阶段先做 S4 配套认证及单条应用消息往返，验证后再加入 Action 4。
