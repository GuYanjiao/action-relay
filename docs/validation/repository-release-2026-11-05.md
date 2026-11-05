# 仓库拆分与自动发布验收

日期：2026-11-05。

- 当前仓库：GuYanjiao/action-relay。源码来源提交 20c061dd093d4b757e8a75e3293a96db9064f004。
- [Automatic release 完整成功](https://github.com/GuYanjiao/action-relay/actions/runs/41979644222)：版本规则回归、所需全部构建与测试、产物校验、GPG 标签签名和发布全部成功。
- [v0.4.6](https://github.com/GuYanjiao/action-relay/releases/tag/v0.4.6) 已公开，Pre-release=false。
- 已从公开 Release 下载全部附件，对照 GitHub asset digest 和 SHA256SUMS.txt 逐项核对，全部一致。下载只在仓库外保存。
- 已抓取并 git verify-tag 验证标签 v0.4.6，签名指纹 9BC5BCD68F629DCE8438F9A15B12374378EAC33B，结果为 Good signature。Git 提交也均使用该签名密钥。
- 现有安装包固定证书保持不变；用户已明确授权两仓库加密 Actions Secrets 保存云端 GPG 签名输入，代码和附件中不含私钥。
- 两仓库只保留 main 为活动远程分支。旧 work/nrf52840-watch 已通过历史合并保存在开发板仓库，原工作树保留为本地归档；没有 force-push 或删除历史提交。
- 本地只做源码/配置静态检查与产物检查，全部编译和可执行回归在云端。没有新增硬件功能或省电比例的真机验收声明。

手机端 0.4.5 首轮自动发布亦成功（41978777669）；0.4.6 是其后的省电调度改动。GPS 生命周期回归覆盖无相机不启动、断开释放、重连恢复与重复就绪不重复订阅。包校验报告 runtime=ART、nativeAbis=[]，SDK JAR 无原生库，因此本轮保留通用 APK，没有声称已完成 Rust/C++ 重构或实测电量收益。
