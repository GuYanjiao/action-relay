# Copilot 接手任务：v0.3 手表优先集成

用户希望通过 VS Code Copilot 承担实现和回归，减少 Codex 用量。当前分支为 `work/v0.3-watch-first`。Codex 已冻结本分支应用文件编辑，后续可负责复核；请由 Copilot 作为本阶段唯一应用代码写入者。

## 已有基础

1. `main` / `v0.2.0` 是用户实测基本可用的手机中转版。新代码与 v0.2 的签名私钥仍保留在本机忽略目录，可覆盖安装；私钥绝不上传。
2. 新 `DjiSession` / `DjiProtocol` 已实现：stop 覆盖未确认 start、重复 stop 合并、start 8 秒 / stop 20 秒窗口、30 秒迟到确认恢复、连续目标状态确认和有限状态重订阅。接口见 `android/tests/STOP_CONFIRMATION.md`。新增状态机检查曾通过 55 项；请复跑。
3. 新 `PhoneGps` / `GpsProtocol` 已单独编译；GPS 编码、真实精度和 NMEA 匹配测试曾通过 44 项。模块只取当前真实 GPS_PROVIDER，不存轨迹。完整 00/17 帧 66 字节，必须 MTU >=69。
4. 新 `FrameQueue` / `DjiBle` 为 control 优先、最新 GPS 替换旧样本、stop 取消未发送 start；尚需纳入整体验证。
5. 新手表 UX 与 18 个 PNG 图标已落盘；192×192 主键、手机/相机/电量/GPS 状态和工具页。新手机 MainActivity 已落盘：168dp 主键、64dp 状态触控、工具面板和记忆/GPS/磁贴入口。界面是 native miuix-inspired，未引入 Compose。

## 尚未接完（优先级从高到低）

### A. 停止确认与手表通信

- RelayHub 仍使用旧的全局 busy 拦截。改为按 `session.canStartRecording()` / `canStopRecording()` 接受操作；stop 必须可覆盖 pending start。使用 `isBusy()`、`getPendingDesired()`、`getCommandPhase()`、`getRevision()`。
- 相机仅在 `hasKnownRecordingState()` 时返回 recording 布尔值，其余 null；过渡状态不报停止。旧 request 的迟到回执不能覆盖新目标或清空新请求。
- 手机 `status` 增加单调 `seq`，watch 丢弃同会话乱序/旧状态；新 session 切换后丢弃已退休 session。结果也带 session / revision，用请求 id 和目标修订关联。
- XiaomiWatchBridge 目前直接并发 send。建立有界、单次在途的发送泵；状态只保留最新一条，terminal result 优先。发送超时可恢复，避免旧回调触发第二个并行泵。
- watch relayController 超时要覆盖新的停止窗口（建议 25 秒）；pending start 时允许显式 stop，替换旧 pending id，不能自动重发 start。confirmed 后请求新状态，但不要被旧 status 回滚。
- 新 UI 需要 controller 发出：phoneConnected、cameraReady、battery、recordSeconds、recordPhase (`idle|starting|stopping|recording|unknown`)、gpsState、gpsEnabled，保留 startEnabled/stopEnabled、recording/pending/noteText。
- snapshot 给手机：canStart/canStop、pendingDesired、recordPhase、watchConnected、gpsState。watchConnected 以手表应用的近期双向消息为准，不能把“SDK service 已连接”当作手表已确认。可由 watch 定期 getStatus 保持轻量心跳。

### B. 手机 GPS 接入

- Root 尚未改 Manifest / RelayService / RelayHub。精确定位同时请求 FINE+COARSE；只获 COARSE 时不启动 GNSS 推送。
- 添加 location 前台服务类型及对应权限；无须后台定位权限。从可见 Activity 响应用户 GPS 开关开启定位 FGS。MainActivity 已按 `RelayService.RECONFIGURE` 调用，请实现常量和处理。
- Hub 提供 `gpsEnabled:boolean`、`gpsState:String`、`gpsAccuracy:float`、`gpsSpeed:float`（m/s）、`setGpsEnabled(boolean)`。先提升服务类型再启动 PhoneGps；关闭时立即 stop/清空 GPS 样本。
- 每秒取最新有效 fix 并按 elapsedMillis 去重；只在协议 ready、MTU够、无控制确认时送低优先级 GPS。缺测/过期/精度不够停止推送。GPS 不积压，不让它拖慢 stop。
- UI GPS phase：off/searching/quality/ready/stale/denied/paused。ready 表示手机完整 GNSS fix 有效，不表示已经验证视频嵌入成功。

### C. 记忆与快捷磁贴

- MainActivity 已引用 `rememberedCameraName`、`rememberedCameraAddress`、`reconnectRememberedCamera()`、`autoReconnectEnabled`、`setAutoReconnect(boolean)`，请实现本地偏好。保留配对标记和随机控制器身份。
- 自动重连是恢复已选相机连接，不是自动录像。拒绝配对后不循环弹窗，停止中转后不重连。GPS 开关可记忆意图，但不得在没有可见启动/权限的冷后台自行开始定位。
- 新建 `RecordingTileService` 并提供 `static requestAdd(Activity)`。声明 BIND_QUICK_SETTINGS_TILE；Android 14+ 使用 PendingIntent 版 startActivityAndCollapse。磁贴按实际 camera 状态更新：可用时发明确 start/stop；未就绪打开应用设置/连接，绝不盲目 toggle 相机协议。锁屏操作按系统解锁流程处理。

### D. 构建、回归与交付

- 两端改为 v0.3.0 / versionCode 3。build.ps1 与 verify-pair.mjs 现在硬编码 0.2.0，需要改为读取当前 manifest 或参数。
- 使用现有本地证书；脚本不应替换密钥。`scripts/build-local.ps1` 可找到本机现成工具，不用重下载 SDK。
- 回归覆盖乱序 status、确认后旧 recording 回报、start 未确认时 stop、慢保存/迟到确认、重复点击、断线、GPS 数据量不阻塞 stop、磁贴状态恢复和权限拒绝。
- 老 v0.2 测试如果与新“连续两次状态确认”冲突，更新期望和输入时序，勿通过取消关键断言掩盖行为。
- 运行测试、编译 APK/RPK、签名和包名校验。将安装包保存 artifacts/（gitignored），在 COPILOT_STATUS.md 写测试结果和包路径。还未真机验证时明确写出。
- 可以提交并推送本开发分支；不要合并 main 或发布 release。用户先实机验证停止保存、锁屏和 Mimo 的 GPS 仪表盘数据。

## 官方依据

- DJI 源码参考在本机相邻 `../dji-protocol/`，公开源：<https://github.com/dji-sdk/Osmo-GPS-Controller-Demo>。
- Xiaomi Interconnect：<https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html>。
- Android Tile：<https://developer.android.com/develop/ui/views/quicksettings-tiles>。
- 构建细节：`开发说明.md` 与 build.ps1；界面短枚举以以上约定为准。
