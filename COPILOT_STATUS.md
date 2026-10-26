# 接手状态

- 当前：A/B/C 已接入，D 阶段正在进行构建与交付准备。
- 应用文件写入者：Copilot（Codex 已冻结应用编辑）。
- 分支：`work/v0.3-watch-first`。
- 基线：`main` / `v0.2.0`；本分支目标为 `v0.3.0` / `versionCode 3`。
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
  - APK SHA-256：`0317e59e1b09d0fe1568c8e9cac3f217f56caa125396205c7cb7315ba9066769`
  - RPK SHA-256：`f66847ee491b330f454193abd4c51e3207d84000dc442b3e5f3adfd3a479f46e`

## 尚未完成 / 阻塞

- Android 全量编译已完成；首次编译发现并修复 `RecordingTileService` 的无效设置常量。
- Vela 编译后页面测试已通过。
- APK/RPK、签名校验和 `artifacts/` 交付路径已生成。
- 云编译工作流已在 GitHub runner 上成功执行（run `40266562983`，提交 `95a11f5`）。
- 上一次 GitHub Actions 云编译失败在 SDK 初始化阶段：`android-actions/setup-android@v3` 默认尝试安装已废弃的 `tools` 包，尚未进入项目编译。
- 尚未完成真实 Watch S4、锁屏停止保存和 Mimo GPS 仪表盘验证。

## 下一步

1. 已修复并验证云编译工作流：显式向 `setup-android` 传入 `platform-tools`、`platforms;android-36` 和 `build-tools;36.0.0`，避免安装废弃的 `tools` 包。
2. 云端 artifact：`action-relay-0.3.0-95a11f5611061d70fda23f82291809be2910c400`，SHA-256：`4e12a51e3522ede78fcfe0de565721df05e9c2309899a576d4964a362e0d38d2`，有效期至 2026-11-25。
3. 下载并保存 Actions artifact，随后进行 Watch S4、锁屏停止保存、GPS 和磁贴真机验证。
4. 等待用户实机验证后再决定是否创建 release。
