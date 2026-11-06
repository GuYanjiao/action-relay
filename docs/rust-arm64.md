# Rust ARM64 核心

手机版的协议和传输队列核心使用 Rust；按用户要求，本轮只提供 `arm64-v8a`。手表端仍是 Vela JavaScript/UX，开发板仓库不受本次重构影响。

## 迁移范围

| 职责 | 实现 |
| --- | --- |
| DJI 帧生成、CRC16/CRC32 与整帧校验 | Rust；CRC 查表在编译期生成 |
| BLE 通知分片重组、连续帧、噪声恢复 | Rust；每次通知一次批量 JNI 调用 |
| 控制优先、停止覆盖未发送启停、GPS合并/过期、断线清空 | Rust；固定容量环形队列 |
| JNI 参数与缓冲区传递 | 薄 C 桥，不承载协议或队列策略 |
| Android 蓝牙、GNSS、小米 SDK、前台服务和界面 | Kotlin Android 接口层；Compose / miuix 界面 |
| 相机控制确认、回放/拍照状态机、定位质量判定 | Kotlin 实现保留原有确认逻辑，由既有 Java 测试验证；调用 Rust 协议接口 |

生产代码没有静默 Java 协议/队列回退。缺失原生库或不兼容 ABI 会明确失败，发布校验也会拒绝缺少 ARM64 库的包。并非整个 Android 应用都已重写为 Rust。

## 内存与调用约束

- 流和队列状态存放在 JVM 持有的固定容量直接缓冲区，Rust 只在同步 JNI 调用期间借用。没有跨调用裸指针、句柄注册表或需要后台回收的原生对象。
- Kotlin 适配器对同一状态串行调用，Rust 不持有 JNI 引用或 JNIEnv，也不创建线程、计时器或唤醒锁。
- 通知输入最多8192字节，DJI 帧最多1023字节，未完成帧最多1023字节；普通控制最多32条，另留一个抢占停止位置，GPS只留最新一条。控制过期交回 Android 断开处理，不能静默发送或重放。
- Rust release 使用 LTO、单 codegen unit、通用 ARM64 目标，避免使用仅开发机器支持的 CPU 指令。APK 原生库不压缩，ELF与ZIP按16KB页要求校验。

## 云端验证

`rust-core.yml` 固定 Rust 1.99.1、NDK 30.0.16248370 和 Android API31。先运行 Rust 单元测试与 Clippy，构建 Linux JNI 后执行现有 Java 协议、GPS、确认状态机和队列回归；新增3000轮随机 CRC/帧对照使用独立 Java 位运算参考实现，另测非法缓冲区与损坏输入。

随后交叉构建 Android ARM64 库，再交给 APK/RPK 工作流。打包校验要求唯一原生 ABI 为 arm64-v8a、ELF64/AArch64、16KB页对齐、固定签名及同版 RPK，并记录原生库 SHA-256。Rust 标准库许可文本随 APK 分发。

Kotlin / Compose 接入后，AndroidX 图形依赖也含原生库。验收覆盖包内每个原生库的 ABI、ELF 和16KB对齐，报告记录各库摘要；不只验证 Rust 库。APK 还必须包含应用、界面、服务、磁贴和 JNI 的固定入口类。

上述结果证明构建、协议对照和模拟控制行为，不能替代手机上的 JNI 加载、蓝牙控制和功耗实测。尚未获得 Android 真机 Rust/旧版 Java 的 A/B CPU、延迟或电量数据，不宣称语言迁移本身带来固定比例的性能提升。ARMv7 后续另行交叉编译与验收。
