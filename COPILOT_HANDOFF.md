# 当前交接索引

当前版本 0.3.2 / 5，主分支 main。GPS 启动与手表界面修改已完成代码检查，固定签名云端构建和回归已通过。硬件验证单独列在项目状态中。

- [项目说明与安装](README.md)
- [项目状态](COPILOT_STATUS.md)
- [云端验收记录](docs/cloud-acceptance-0.3.2.md)
- [Copilot 检查清单](docs/copilot-checklists/)
- [自动派发方式](docs/copilot-workflow.md)

后续只在本地编辑和静态检查，不得本地编译或运行会调用编译器的测试；构建、回归和安装包签名在 GitHub Actions 执行。Git 提交/标签使用用户 OpenPGP 签名。签名私钥与口令不得进入源码或日志。

旧 v0.3 交接单已归档至 [docs/history](docs/history/2026-10-26-handoff-v0.3.md)。其中的本机打包、等待集成等状态已过时，不能作为新的执行要求。
