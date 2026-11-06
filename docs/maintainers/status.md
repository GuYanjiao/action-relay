# 项目状态

更新：2026-11-05。

本仓库为手机中转版：Android 手机负责相机 BLE 和真实 GNSS，S4 为控制界面。开发板版独立于 [action-relay-nrf52840](https://github.com/GuYanjiao/action-relay-nrf52840)，避免版本、分支和发布混用。

手机开发基线 0.4.4 / 13，自动发行版本由 release-policy.json 和发布工作流序号确定。既有基本录像经用户测试；0.4.4 回放/拍照行为和 GPS 嵌入等设备差异仍以真机验收为准。既有验收记录保留在 docs/validation/。

已配置云端自动版本、固定安装包签名、GPG 标签签名和完整附件发布；[自动发布与附件核验已通过](../validation/repository-release-2026-11-05.md)。手机代码已恢复为不包含开发板图标/会话适配的版本，开发板手表适配留在新仓库。

当前公开手机版本 **0.4.7 / versionCode16**：Rust核心已用于帧生成、CRC、通知重组和控制/GPS队列；仅支持ARM64。保留0.4.6的GNSS消费者门控与后台降频。云端完整验证和自动发布均成功，见 [Rust验收](../validation/rust-arm64-2026-11-05.md)。Android适配、控制确认状态机仍为Java；没有实测能耗或跨语言真机性能结论。


2026-11-06：在 work/kotlin-android 分阶段迁移 Android 到 Kotlin，Rust ARM64 核心不变；准备接入 Compose / miuix 和 Gradle。迁移尚未通过云端检查，不能视为可安装版本。
