# 当前任务：nRF52840 S4 BLE 中转

2026-10-29。用户要求持续推进并分阶段保存。保持现有 Android/Watch 0.4.4 主线，新增独立 firmware/nrf52840 路径；不本地编译，不自动刷写，不调用 Copilot。

## 已完成

- 已找到针对 S4 的 FE95 BLE 配套源码，详见 docs/s4-nrf52840-ble-followup.md；用户确认本机有心率广播。
- 阶段 1 诊断固件源码：USB 命令行，扫描、指定地址连接、枚举服务/特征、订阅心率；不配对认证、不自动连接、不发相机命令。
- 心率解析边界测试已编写，仅在云端执行。

## 正在进行

新增独立 GitHub Actions，按 Zephyr 4.5.0 的 promicro_nrf52840/nrf52840/uf2 板定义构建并上传产物。首次运行 40811018203 在 zephyr-export 导入 jsonschema 时失败，已将 requirements 安装移到 export 前；尚未通过 ARM 编译。

实物启动盘 F: NICENANO，Board-ID nRF52840-nicenano，UF2 Bootloader 0.6.0，S140 6.1.1。仅检查，未刷写或擦除。下一步核对 UF2 写入地址在应用分区 0x26000..0xec000 内。

阶段 2A 的可移植认证派生、双方证明计算及合成向量测试已签名保存 bf8aaa4；不含真实 AuthKey，不代表认证成功。测试等待下一次云端运行。

运行 40811787301：心率解析、合成认证向量测试及工具链安装通过；构建在读取应用 VERSION 时失败，已改为 Zephyr 要求的键值格式。后续运行同时包含 UF2 分区检查。实物备份及摘要见 docs/validation/nrf52840-board-2026-10-29.md。

## 后续顺序

云端编译与静态检查 → 核对 Board-ID/Bootloader、服务枚举与心率通知 → 实现并验证配套认证 → 应用 ping/pong → 两段连接与 Action 4。

没有用户设备 AuthKey 或真实服务表时可先开发协议编码、解析及离线测试，但不能把离线通过称作认证或中转成功。阶段进度每次签名提交保存，重要证据写 docs/validation/。
