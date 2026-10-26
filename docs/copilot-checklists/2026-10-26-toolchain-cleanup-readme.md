# 2026-10-26：仓库介绍与开发文档整理

仅编辑 README.md 和新建 docs/development.md；其他文件由 Codex 同时处理，不要修改。阅读 AGENTS.md。使用文件阅读/编辑工具，不调用终端、不运行测试、不编译、不 Git、不启动其他 worker。完成后写结果到派发给你的 result.md。

README 面向首次访问仓库的开发者和使用者：简洁中文项目介绍、Android 手机中转连接 Xiaomi Watch S4 与 DJI Action 4 的架构、主要功能、支持范围与实验性 GPS/真机边界、下载同次 Actions 配套 APK/RPK 和安装授权步骤、开发文档和第三方说明链接。不要写成向项目主人汇报工作、个人设备报告、交接单或验收流水账。不宣称 Compose Miuix、全兼容/零延迟。版本保持 0.3.3，不虚构新 Release 或 LICENSE。

新 docs/development.md 说明 native Java + Vela，云端可复现构建与签名、测试入口/目录、消息契约和状态真实性；本地仅编辑与静态检查，不能本地编译。最新稳定工具链由 Codex 更新，本次计划 Node 26.12.1、Temurin JDK 27、Android Platform 37.2、Build Tools 37.0.0、aiot-toolkit 稳定 2.0.5、jsc 1.0.10；版本最终以 .github/workflows 和 package lock 为准。Java --release 8 是 Android 字节码目标，不是旧 JDK；targetSdk 36 保持现有权限/行为契约。若工具链需要云端兼容调整，以实际配置为准，不夸称所有工具都最新。

新目录布局由 Codex 处理：状态 docs/maintainers/status.md；云端记录 docs/validation/cloud-acceptance-0.3.2.md 与 0.3.3.md；Copilot 工作流 docs/copilot-workflow.md；保留 docs/copilot-checklists 全部历史。根目录 COPILOT_HANDOFF.md、旧开发说明.md、旧使用与设计说明.md 和重复 docs/history 将删除（历史保存在 Git），不要在新文档中链接它们。build.ps1 与 verify-pair.mjs 本轮暂保留根目录以免破坏入口。

README 的重点是产品和贡献入口，私钥存放位置、个人 Git 签名指纹、个人设备名称不应出现在 README。开发文档可列 Actions Secrets 名称，但不读取任何私钥/口令。无截图实物图不要引用旧 preview。
