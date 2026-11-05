# 自动版本与发布

推送构建相关改动到 `main` 后，Automatic release 工作流自动运行；只改文档不会发版。也可在 Actions 手动运行。开发分支和 PR 不发布 Release。

`release-policy.json` 是版本基线。实际构建版本 = 基线的 patch 加该仓库发布工作流的 `run_number`；安装版本码 = `baseVersionCode + run_number`。两仓库计数独立，失败可以留下版本空号；同一次运行重试保持原版本。不要删除重建 release.yml 来重置计数，也不要降低版本基线。版本只注入 runner 的检出目录，源码中的版本表示开发基线，不生成版本更新提交，不循环触发工作流。

必须完整构建、回归通过并核对固定签名与产物摘要，才进入发布。Git 标签使用已授权的个人 GPG 密钥签名并验证；APK/RPK 继续使用固定证书。先上传到 Release 草稿，全部附件成功后才公开；已公开附件不可被重跑覆盖。序列化发布避免并发抢同一个版本。

Actions Secrets：

- ACTION_RELAY_KEYSTORE_BASE64、ACTION_RELAY_STORE_PASSWORD、ACTION_RELAY_KEY_PASSWORD：固定安装包证书。
- ACTION_RELAY_GPG_PRIVATE_KEY、ACTION_RELAY_GPG_PASSPHRASE：用户于2026-11-05明确授权云端使用的 Git 签名密钥。仅发布任务导入临时密钥环，退出时清理，不写入附件。

每个 Release 包含 build-info.json、SHA256SUMS.txt 和安装包验证报告，可追溯源码提交及云端运行。构建通过不代表新增硬件功能已经实测。若发布任务失败，优先重跑失败任务，不推空提交重新占用版本。
