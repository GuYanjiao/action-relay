# 截图 CI 修复检查清名单

- [x] 阅读仓库 AGENTS.md、维护状态和开发文档。
- [x] 分析 Actions job 138323977517 的日志。
- [x] 确认根因：截图脚本在 `/usr/bin/sh` 下执行，dash 不支持 `pipefail`。
- [x] 将主工作流及已跟踪的同名副本改为 POSIX 兼容的 `set -eu`。
- [x] 静态验证工作流差异；YAML 可解析且两份副本不再包含 `pipefail`。
- [ ] 在 GitHub Actions 重新运行截图工作流。
- [ ] 核对截图 artifact 和硬件状态分离验收。
