# 当前任务：手机版仓库与自动发布

2026-11-05：本仓库只维护 Android 手机中转版，开发板路线迁往 [action-relay-nrf52840](https://github.com/GuYanjiao/action-relay-nrf52840)。保留已有 Git 历史和 v0.2.0 回滚标签，不 force-push。

- 手机源码 android/ + watch/；固定签名身份保持不变。
- main 的构建相关改动自动递增版本、完整回归后创建签名标签和正式 Release；文档改动不自动发版。
- 流程实现见 release.yml、release-policy.json 和 docs/releasing.md；首轮及省电改动云端均已成功，证据见 docs/validation/repository-release-2026-11-05.md。
- 本地只做静态检查，不编译或运行回归，不再调 Copilot。
- 开发板 FE95/认证、UF2 和原 HR→ANT+ 组合开发的接续记录已迁到新仓库 docs/maintainers/board-development.md，本轮不重复实机扫描或刷写。

## Rust ARM64 重构（2026-11-05）

用户要求使用 Rust 重构，随后明确先只支持 ARM64，ARMv7以后补。当前迁移帧生成、CRC校验、通知重组与控制/GPS有界队列，Android SDK和控制状态机仍走原接口。Rust 1.99.1、NDK 30.0.16248370，JNI薄桥不承载协议策略；JVM持有固定直接缓冲区，避免原生句柄泄漏。新增原始Java回归、随机CRC/帧对照与ABI/16KB对齐检查。最终源码 f0bf1ae / 云端41982442857已完整成功并自动发布v0.4.7；已验证GPG标签、公开APK摘要、ARM64原生库和16KB对齐。详见 docs/validation/rust-arm64-2026-11-05.md。未在本地编译，Android真机加载/控制/能耗尚待设备验收。


## Kotlin / miuix 迁移（2026-11-06）

用户已同意将 Android 接口、界面和控制层改为 Kotlin，Rust ARM64 核心保留。工作分支 work/kotlin-android。
16个应用源文件已迁移，原有 Java 回归直接校验 Kotlin 实现并全部通过；UI 使用 Compose / miuix-ui。
已修复 AGP9 的 Kotlin 源目录及 manifest SDK 配置，新增 APK 应用/JNI入口校验。
最终预览云端42161754772（源码a487056）已全部成功；接下来合并main，由现有流程递增版本并签名发布。
详见 docs/validation/kotlin-miuix-2026-11-06.md。本地没有编译，未修改开发板仓库或原心率项目。
