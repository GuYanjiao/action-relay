# 当前任务：nRF52840 S4 BLE 中转

2026-10-29。用户要求持续推进并分阶段保存。保持现有 Android/Watch 0.4.4 主线，新增独立 firmware/nrf52840 路径；不本地编译，不自动刷写，不调用 Copilot。

## 已完成

- 已找到针对 S4 的 FE95 BLE 配套源码，详见 docs/s4-nrf52840-ble-followup.md；用户确认本机有心率广播。
- 阶段 1 诊断固件源码：USB 命令行，扫描、指定地址连接、枚举服务/特征、订阅心率；不配对认证、不自动连接、不发相机命令。
- 心率解析边界测试已编写，仅在云端执行。

## 正在进行

新增独立 GitHub Actions，按 Zephyr 4.5.0 的 promicro_nrf52840/nrf52840/uf2 板定义构建并上传产物；实物 Bootloader 尚未确认，不能直接承诺 UF2 可刷。

## 后续顺序

云端编译与静态检查 → 核对 Board-ID/Bootloader、服务枚举与心率通知 → 实现并验证配套认证 → 应用 ping/pong → 两段连接与 Action 4。

没有用户设备 AuthKey 或真实服务表时可先开发协议编码、解析及离线测试，但不能把离线通过称作认证或中转成功。阶段进度每次签名提交保存，重要证据写 docs/validation/。
