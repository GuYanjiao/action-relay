# 项目状态

更新：2026-11-22。

2026-11-25：修复 Android 截图 Actions 在 POSIX `sh` 下因 `pipefail` 选项失败的问题，改用 `set -eu`；嵌入脚本已通过 `dash -n` 与选项检查，`git diff --check` 通过。尚待云端手动截图工作流复跑确认。

当前公开手机版本 **0.4.9 / versionCode18**：新增手机端手动检查更新与视频/拍照模式选择，个人GPG签名提交c36ceba已合入main。预览构建44976801900与正式发布44977354655全部通过；已下载并核对公开APK/RPK、摘要、同版信息、ARM64及16KB对齐，并验证个人GPG标签。所有构建和可执行测试均在云端；开发板仓库独立。详见[本轮验收](../validation/updates-mode-2026-11-22.md)。

本仓库为手机中转版：Android 手机负责相机 BLE 和真实 GNSS，S4 为控制界面。开发板版独立于 [action-relay-nrf52840](https://github.com/GuYanjiao/action-relay-nrf52840)，避免版本、分支和发布混用。

手机开发基线 0.4.4 / 13，自动发行版本由 release-policy.json 和发布工作流序号确定。既有基本录像经用户测试；0.4.4 回放/拍照行为和 GPS 嵌入等设备差异仍以真机验收为准。既有验收记录保留在 docs/validation/。

已配置云端自动版本、固定安装包签名、GPG 标签签名和完整附件发布；[自动发布与附件核验已通过](../validation/repository-release-2026-11-05.md)。手机代码已恢复为不包含开发板图标/会话适配的版本，开发板手表适配留在新仓库。

0.4.8 / versionCode17完成Android自有代码Kotlin迁移、Compose / miuix-ui界面；Rust承担帧、CRC、重组和队列，仅发布ARM64。后台降频、GNSS消费者门控、设备记忆和开启中转磁贴保持。签名合并提交6fc60bb与正式云端42162515798的历史记录见 [Kotlin验收](../validation/kotlin-miuix-2026-11-06.md)。尚未完成新版真机界面/锁屏控制和能耗对照，不宣称语言迁移带来节电比例。精确分辨率/FPS写入和S4系统卡片仍缺公开接入接口；新增检查更新页面和模式控制仍需设备验收。
