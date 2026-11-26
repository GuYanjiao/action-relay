# 说明书云端截图工作流验收

2026-11-26核对main进展：v0.4.9应用代码与安装包未发生变化；新增的截图工作流此前连续失败，Rust核心任务仍通过。Copilot分支中的POSIX shell修补未解决进程隔离问题，本次在现有main上修复，没有合并旧分支覆盖他人历史。

## 已修复的问题

- emulator-runner将多行script拆成独立sh进程，既不支持pipefail，也不能保留APK变量或Python heredoc。新增scripts/capture-android-screenshots.sh，以单行bash调用，并在手机任务检出仓库。
- API33 x86_64镜像安装ARM64发布APK时报NO_MATCHING_ABIS。API35 Google APIs镜像实测回报x86_64,arm64-v8a，成功安装原v0.4.9 APK；没有为截图修改发布ABI或重打包APK。
- 检查更新页返回会回到主页，截图脚本现重新进入工具页后再打开连接记录。缺少必需页面直接失败，避免少图成功。
- 手表布局预览缺少中文字库，已安装fonts-noto-cjk，重新核对中文显示。
- 删除误放在.github/workflows/.github/workflows下的重复工作流，保留有效入口。

## 云端结果

[45743909447](https://github.com/GuYanjiao/action-relay/actions/runs/45743909447)首次完整通过手机六页与手表三页任务；检查图像后发现手表字体方框，继续修复。

[45744545773](https://github.com/GuYanjiao/action-relay/actions/runs/45744545773)包含字体修复，两个任务均通过。手机附件android-screenshots-v0.4.9-light含主页、相机、GPS、工具、检查更新和连接记录六张运行截图；watch-ui-previews-v0.4.9含主屏、模式与连接设置三张布局预览。

手机图来自正式APK的模拟器运行，没有连接真实S4和相机。手表图为浏览器按界面布局与图标绘制的预览，示例状态不是设备测量。说明书应明确两类来源，不作为真机配对、录像、GPS写入或功耗验证。

修复提交经既有云端个人GPG签名后快进main，正式版本仍为v0.4.9；未进行本地应用编译。说明书个人申报材料在仓库外独立整理。
