# 接手状态

## 最终复核：0.3.1（2026-10-26）

- Copilot 两轮实现已结束；Codex 接手补齐生命周期协调器、非关键监听异常的兼容处理、定位诊断和编译后页面测试，当前无并行应用写入者。
- 已修复运行中开启 GPS 未订阅定位的问题；服务成功提升 location 类型后配置 GPS，GPS 重配不重建相机/手表连接。失败可重试，关闭与停止清理，失效速度不残留。
- 四个手表图标为 44px；页面、主容器、工具页与 manifest 均纯黑；标题、绿点、四个图标下的文字、录制按钮下的文字已去掉。录像图形状态、计时与工具入口保留。
- 独立复跑通过：协议 43 项、停止状态机 55 项、GPS 编码/质量 44 项、GPS 生命周期 10 项、队列检查、11 个手表控制器场景，以及真正展开条件模板并点击编译后事件的页面检查。
- 最新 APK/RPK 构建、APK 密码学签名验证、同证书/CRC/包内私钥排除检查均通过；版本 0.3.1 / 4。产物取 android/build 与 watch/dist 的最新构建，旧的下方散列仅作为历史记录。
- 仍需手机/Watch S4/Action 4 真机复测：系统位置访问记录、真实 GNSS 完整测量、圆屏显示、视频 GPS 数据。当前自动测试不提供这些硬件证明。
- 后续 Git 提交与标签按用户指定 OpenPGP 密钥签名；不因此更换 APK/RPK 原签名。

- 当前：GPS 启动生命周期与手表精简 UI 已完成，D 阶段构建交付已完成。
- 应用文件写入者：Copilot（Codex 已冻结应用编辑）。
- 分支：`work/v0.3-watch-first`。
- 基线：`main` / `v0.2.0`；本分支目标为 `v0.3.1` / `versionCode 4`。
- 发布状态：已完成本地 APK/RPK 构建和包校验；尚未真机验证，不创建 release。

## 本轮已完成（2026-10-26）

- `RelayHub` 改用 `DjiSession.canStartRecording()` / `canStopRecording()`，允许停止覆盖未确认的开始，不再用全局 `busy` 阻塞停止。
- 手机 status 增加 `seq`、`session`、`revision`、`canStart`、`canStop`、`pendingDesired`、`recordPhase`、`watchConnected` 和 GPS 状态字段。
- 结果消息携带 `session` / `revision`；手表控制器丢弃同 session 的乱序 status，并校验结果 session。
- `XiaomiWatchBridge` 改为单次在途发送泵：状态消息只保留最新一条，result 优先，5 秒发送超时可恢复。
- 手表控制器支持 8 秒 start、25 秒 stop 超时；pending start 时允许显式 stop 替换旧 pending。
- 接入 `PhoneGps`：精确定位权限、location 前台服务类型、有效 fix 的低优先级 GPS 队列发送；控制确认期间不发送 GPS。
- 实现记忆相机、可选自动重连（不会自动录像；拒绝授权后不循环重连）。
- 新增 `RecordingTileService`，磁贴只在 `canStart` / `canStop` 明确可用时发送 start/stop，否则打开应用。
- Android/Watch 版本更新到 `0.3.0` / `3`；`build.ps1` 和 `verify-pair.mjs` 支持版本名和签名密码参数。
- 更新协议回归测试以覆盖连续两次稳定状态确认。

## 已执行测试

- `DjiSessionTransitionTest`：55 项通过。
- `DjiProtocolSelfTest`：43 项通过。
- `FrameQueueTest`：控制优先、停止覆盖、GPS 合并/过期、断线清空通过。
- `watch/tests/controller.test.mjs`：11 个场景通过。
- `node --check verify-pair.mjs`：通过。
- `git diff --check`：通过。
- 已为 `work/v0.3-watch-first` 加入 GitHub Actions 云编译工作流：Windows runner、JDK 17、Android API 36、Watch 构建、编译后测试、包校验和 APK/RPK artifact 上传。
- 已生成 `watch/package-lock.json`，云编译使用 `npm ci`。
- 已在可访问 Android SDK 的本机完成 `build.ps1`：Android API 36、Build Tools 36、JDK 17；APK 和 RPK 均成功生成。
- 编译后手表测试：通过。
- `verify-pair.mjs`：通过同一证书、CRC、Watch feature、APK DEX/Manifest 和私钥泄露检查。
- 本地交付产物：
  - `artifacts/ActionRelay-0.3.0.apk`
  - `artifacts/cn.local.actionrelay.debug.0.3.0.rpk`
  - APK SHA-256：`60cf8bc48cd79abb4d30cd3528a32fbefa7b11657c227a78362b8381af2e1453`
  - RPK SHA-256：`389b64e4fddae4def6aaf91baf8fb7af37ea9a2726bbec2394e9de01cfd7570a`

## 尚未完成 / 阻塞

- Android 全量编译已完成；首次编译发现并修复 `RecordingTileService` 的无效设置常量。
- Vela 编译后页面测试已通过。
- APK/RPK、签名校验和 `artifacts/` 交付路径已生成。
- 云编译工作流已在 GitHub runner 上成功执行（run `40266562983`，提交 `95a11f5`）。
- 上一次 GitHub Actions 云编译失败在 SDK 初始化阶段：`android-actions/setup-android@v3` 默认尝试安装已废弃的 `tools` 包，尚未进入项目编译。
- 尚未完成真实 Watch S4、锁屏停止保存和 Mimo GPS 仪表盘验证。

## 本轮 GPS/手表 UI 交付（2026-10-26）

- `RelayService.RECONFIGURE` 现在先提升 location 前台服务类型，再调用 `RelayHub.reconfigureGps()`，不重建 BLE、手表连接或相机 session。
- `PhoneGps` 保留 GPS_PROVIDER 位置订阅，即使可选 GNSS/NMEA 附加监听失败；记录去重的订阅异常和首个真实 fix 诊断，不含坐标，并区分权限、定位开关、过期和启动失败。
- 手表主界面改为纯黑背景，删除 ACTION 4/绿点/状态文字/录制标签，四个状态图标放大到 44px，保留计时、主键、刷新和工具页。
- Android/Watch/CI/校验默认版本统一为 `0.3.1` / `versionCode 4`。

## 本轮精确检查

- `scripts/test.ps1 -Area all`：43 协议、55 会话、44 GPS、FrameQueue、11 手表控制器场景、编译后页面测试全部通过。
- `scripts/build-local.ps1`：APK/RPK 构建成功；产物已保存到 `artifacts/ActionRelay-0.3.1.apk` 和 `artifacts/cn.local.actionrelay.debug.0.3.1.rpk`。
- `node --check verify-pair.mjs` 与 `node verify-pair.mjs`：通过 CRC、同一证书、Watch feature、APK DEX/Manifest、无私钥检查。
- `git diff --check`：通过。
- 尚未完成真机验证：Watch S4 实际 GPS 开关/定位访问记录、锁屏停止保存、视频 GPS 仪表盘嵌入、磁贴运行时行为。
- 本次复核未创建或推送提交；派发的本地 Copilot run 未产生 `result.md`，已在当前工作树完成同一清单的复核与验证。

## 下一步

1. 已修复并验证云编译工作流：显式向 `setup-android` 传入 `platform-tools`、`platforms;android-36` 和 `build-tools;36.0.0`，避免安装废弃的 `tools` 包。
2. 云端 artifact：`action-relay-0.3.0-95a11f5611061d70fda23f82291809be2910c400`，SHA-256：`4e12a51e3522ede78fcfe0de565721df05e9c2309899a576d4964a362e0d38d2`，有效期至 2026-11-25。
3. 下载并保存 Actions artifact，随后进行 Watch S4、锁屏停止保存、GPS 和磁贴真机验证。
4. 等待用户实机验证后再决定是否创建 release。

## 本次 Copilot 云签名配置任务（2026-10-26 run 20261026-131509-fd030b6e）

- 已完成：两端版本升为 `0.3.2` / `versionCode 5`；Actions 恢复固定 PKCS12、强制别名 `guyanjiao-release`、检查三项 signing secret，并清理 runner 上的 keystore/private.pem。
- 已完成：`ExportSigning.java` 不再接受命令行口令；`build.ps1` 不再生成或回退测试 keystore，apksigner 仅从环境变量读取口令；公开证书指纹配置位于 `signing/release-signing.json`。
- 已完成：workflow 增加 Android/Watch 版本一致性检查和 `apksigner verify`；`verify-pair.mjs` 检查固定证书指纹、同证书、CRC、包结构和私钥排除。
- 本地精确检查：`node --check verify-pair.mjs` 通过；JSON 静态解析通过；PowerShell 脚本解析通过；`git diff --check` 通过；`scripts/build-local.ps1` 明确拒绝本地构建并通过拒绝检查。
- 按用户要求未运行 Java/Android/Vela 编译、`scripts/test.ps1` 或本地 APK/RPK 构建。未提交或推送。
- 待云端：推送后配置 `ACTION_RELAY_KEYSTORE_BASE64`、`ACTION_RELAY_STORE_PASSWORD`、`ACTION_RELAY_KEY_PASSWORD`，运行 Actions 完整测试、签名/包校验并下载 artifact。仍待真机验证 Watch S4、锁屏停止保存、手机 GNSS 质量、视频 GPS 嵌入和磁贴运行时行为。
