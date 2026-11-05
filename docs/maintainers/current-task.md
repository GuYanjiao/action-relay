# 当前任务：手机版仓库与自动发布

2026-11-05：本仓库只维护 Android 手机中转版，开发板路线迁往 [action-relay-nrf52840](https://github.com/GuYanjiao/action-relay-nrf52840)。保留已有 Git 历史和 v0.2.0 回滚标签，不 force-push。

- 手机源码 android/ + watch/；固定签名身份保持不变。
- main 的构建相关改动自动递增版本、完整回归后创建签名标签和正式 Release；文档改动不自动发版。
- 流程实现见 release.yml、release-policy.json 和 docs/releasing.md；首轮及省电改动云端均已成功，证据见 docs/validation/repository-release-2026-11-05.md。
- 本地只做静态检查，不编译或运行回归，不再调 Copilot。
- 开发板 FE95/认证、UF2 和原 HR→ANT+ 组合开发的接续记录已迁到新仓库 docs/maintainers/board-development.md，本轮不重复实机扫描或刷写。
