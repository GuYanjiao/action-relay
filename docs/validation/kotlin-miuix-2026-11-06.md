# Kotlin / Compose / miuix 迁移验收

日期：2026-11-06。范围为手机中转仓库；开发板仓库与原心率 ANT+ 项目未修改。

## 变更范围

- Android 应用的16个 Java 源文件全部迁为 Kotlin，包括蓝牙、GNSS、前台服务、Xiaomi SDK 适配、控制确认状态机和 JNI 调用封装。
- 手机界面使用 Compose 与 miuix-ui 0.9.4 的主题、卡片、按钮和开关；主题跟随系统，保留授权、相机记忆、GPS、日志和开启中转磁贴。
- Rust 协议与队列核心、JNI 导出名、手表消息格式保持兼容。仍只发布 ARM64。
- 引入固定 Gradle Wrapper 9.6.0、AGP 9.5.0、Kotlin / Compose 编译器 2.4.21。应用字节码目标17；Android最低31、target36保持不变。
- Wrapper JAR 的 SHA-256 与 Gradle 官方公布值一致：`497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`。发行 ZIP 也在 wrapper 配置中固定摘要。

## 云端核心回归

源码8e0f07c的 [云端构建42159527533](https://github.com/GuYanjiao/action-relay/actions/runs/42159527533) 已通过核心任务：

- Rust：3个单元测试、Clippy，Linux JNI 构建及 Android ARM64 交叉构建。
- Kotlin 生产实现 + 原有 Java 测试：3000轮独立 CRC/帧对照；43项协议/会话、48项遥测、36项回放/拍照、55项停止转换和44项 GPS 协议/质量检查。
- 传输优先级、停止覆盖、GPS合并/过期、重连取消与预算、手表结果队列以及 GPS 生命周期/消费者门控全部通过。
- 固定签名识别：10项兼容/拒绝检查通过。

迁移期间由既有测试发现并修正 Kotlin 原始数组空判断、GPS空输入异常类型和新版 JVM 默认方法编译选项问题。未放宽原有断言。

## 整包构建阶段

源码3b45f90的 [构建42160821268](https://github.com/GuYanjiao/action-relay/actions/runs/42160821268) 已通过 Kotlin/Compose 编译、R8、APK/RPK构建、固定签名及手表回归。最后包配对校验仍按旧版单一Rust库断言，因此拒绝了新增的AndroidX图形库；已扩展为逐库校验ARM64/ELF/16KB对齐，并记录各库摘要，待完整重跑。

SDK适配确认 `requestPermission` 返回权限列表，回调后改用 `checkPermission` 核实实际授权；消息回调按SDK的非空字节数组契约实现。

## 当前边界

AGP9 源目录与 SDK 配置已按官方迁移文档修正（https://developer.android.com/build/migrate-to-built-in-kotlin），并新增 APK 内应用/JNI入口检查。源码a487056的 [最终预览构建42161754772](https://github.com/GuYanjiao/action-relay/actions/runs/42161754772) 已全部成功：Kotlin/Compose与R8、APK/RPK构建、固定证书签名、手表回归、全部原生库ARM64/16KB校验及5个Android/JNI入口校验。正式版本随后由main自动发布，结果在完成后补记。本地只编辑、静态解析和检查产物；未在本地编译或运行回归。

尚未完成手机上的新界面、JNI、锁屏控制与 GPS 真机回归，未测量与旧版对照的 CPU 或电量。因此不宣称 Kotlin 迁移本身降低功耗或相机确认延迟。
