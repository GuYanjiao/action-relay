# 本机 Copilot 自动交接

后续代码任务可由 Codex 直接调用本机 VS Code 的 GitHub Copilot Agent。用户不需要每次打开启动页、复制任务或选择工作目录。

## 使用流程

1. 在本地仓库明确本次任务和验收条件。给 Copilot 的检查清单单独保存在 `docs/copilot-checklists/日期-主题.md`，完成后随 Git 推送，保留历史记录。
2. Codex 调用 `scripts/start-copilot.ps1 -TaskFile <清单或任务文件>`。脚本定位 VS Code、打开本仓库、开启 Agent，并附带任务文件及 AGENTS.md。
3. 每次交接返回一个独立的 `resultFile` 路径。Copilot 完成后在该文件记录结果；Codex 读取结果、检查实际差异、复核必要测试，再提交并推送获授权的改动。
4. 同一批文件保持一个写入者。Copilot 编写时，Codex 可阅读和检查，不同时改写相同文件。上一轮未完成时不重复派发。

日常任务由 Codex 执行以上入口即可。应用文件提交后由 GitHub Actions 使用固定发布证书构建与回归；本地只做静态检查，不调用安装包编译器。手动验证入口为：

```powershell
.\scripts\start-copilot.ps1 -Probe
```

派发指定清单：

```powershell
.\scripts\start-copilot.ps1 -TaskFile 'docs/copilot-checklists/2026-10-26-v0.3-review.md'
```

`-DryRun` 只展示工作目录和派发路径，不打开窗口、不调用模型。真实派发记录位于已被 `local.*` 规则忽略的 `local.copilot-runs/`；不要把会话日志、敏感信息或临时验证文件提交到 Git。

## 已验证范围

2026-10-26，在本机 VS Code 1.143.1 中通过 CLI 自动创建 Copilot Agent 会话。Copilot 实际读取了 Watch manifest，并写回项目路径、包名 `cn.local.actionrelay`、版本 `0.3.0` 和验证完成标记；并非仅仅打开窗口。

CLI 返回成功只表示派发，不代表任务完成。最终以 result 文件、会话状态、实际文件差异及测试为准。此入口使用正常的 VS Code 窗口和现有 Copilot 登录/权限设置，不安装常驻服务，也不改变安全或隐私设置。登录失效、用量耗尽或明确的权限拦截仍可能需要用户处理。默认模型由现有 Copilot 设置决定。

官方入口说明：[VS Code CLI：启动聊天](https://code.visualstudio.com/docs/configure/command-line#_start-chat-from-the-command-line)。此实现使用编辑器中的 Copilot Agent；与独立“智能体”窗口的布局不同，代码工作目录相同。
