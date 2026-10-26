# 手机 GPS 启动修复与手表界面精简

基于 `8ddb624`，用户已在真机反馈。由本机 VS Code Copilot 负责本轮代码写入，Codex 负责复核、提交和推送。只做本文任务，不执行旧交接单中的其他未完成项目。

## 用户现象与本次目标

- 手机允许位置权限并打开 GPS 后一直显示“定位中”，系统位置访问记录里没有本应用。
- 手表背景改为纯黑；删除顶部 ACTION 4 及绿点；四个状态图标去掉全部下方文字并放大；删除录制按钮下的“待机”文字。
- 保留已有录像遥控、计时、图标状态、刷新与工具入口；手机视觉布局不重做。

## A. GPS 必须真正启动，而不是只改 UI

- 当前 `RelayHub.start()` 在 enabled 时直接 return，`setGpsEnabled(true)` 只保存开关；`RelayService.RECONFIGURE` 最后仍只调用 hub.start()。修复“中转运行中 → 授予精确位置 → 开启 GPS”不触发 PhoneGps.start 的生命周期缺口。
- 服务先成功启用 location 前台服务类型，再调用真实 LocationManager 的定位订阅；重新配置时不重建 BLE/手表连接、不旋转现有相机 session。授权返回/已授权开关/关闭再开启/停止中转都要走完整路径。
- 保留 FINE+COARSE 一起请求，只给粗略位置时清晰提示；不申请后台位置权限，不在冷后台擅自开启定位。
- 启动被系统拒绝时捕获并显示明确错误，状态不能假装正在定位；GPS_PROVIDER 关闭、缺权限、等待首次定位、等待完整测量、过期、暂停与关闭需被正确区分。检查 PhoneGps 实际 phase 与 MainActivity 显示映射的一致性。
- 增加不包含经纬度的诊断：定位订阅成功/失败、收到首个真实定位、等待何种测量；不要记录轨迹或精准坐标。PhoneGps.getState().message 可用于 GPS 详情。
- 不降低 GpsProtocol 的真实性/新鲜度要求来制造 ready，不把“收到水平定位”当作完整 DJI GPS 帧可用；保留控制优先、GPS 不积压。
- 增加针对运行中 GPS 开关生命周期的有意义回归验证，不能只测编码器。无法真机验证时明确写出限制。

官方依据：[location 前台服务](https://developer.android.com/develop/background-work/services/fgs/service-types#location)，[位置运行时权限](https://developer.android.com/develop/sensors-and-location/location/permissions/runtime)。

## B. 手表界面按用户照片精简

- `watch/src/pages/index/index.ux` 的页面/主容器/工具页底色，以及 manifest.display.backgroundColor 全部为 `#000000`。保留按钮本身的颜色；不保留深蓝/近黑底或背景渐变。
- 删除 heading、ACTION 4、绿点，移除对应失效绑定/样式。
- 四个手机/相机/GPS/电池状态图标保留并从 28px 放大到约 44px，使用现有 PNG，等距对齐并确保 466px 圆屏安全区不裁切。
- 删除四个图标下的所有文字（含已连、定位中及电池百分数）。主界面通过图标与颜色表达状态，工具页仍可有连接说明。
- 删除录制按钮下的 capture-label 文本区域，包括“待机”；保留计时、录制/停止/等待/禁用的图形状态与点击行为。必要时调整空隙，但不要缩小已有主触控区域。
- 同步编译后页面测试以覆盖真实主界面结构、图标、状态绑定和录像操作。纯视觉低风险改动不额外堆砌模拟实现的测试。

## C. 构建与交付

- 本次版本两端统一 `0.3.1` / versionCode `4`，同步构建默认值、CI VERSION_NAME 和验证脚本，避免文件名/manifest/测试仍写旧版本。
- 保留本地原签名，不能覆盖 .signing 或上传私钥。不要改签名流程和旧 Release。
- 运行 `scripts/test.ps1` 全套以及本机成对构建、APK 签名和 verify-pair 校验。可在命令环境临时使用 JDK 17 的 bin；工具在相邻 android-tools、s4-probe，无需重下载 SDK。
- 修改应用与相关回归/构建配置即可。可更新 COPILOT_STATUS.md 记录本轮结果。不要修改派发脚本、AGENTS.md、本文或旧检查单。不要执行 git commit/push/merge 或发布 Release，Codex 复核后负责提交。
- 最终在派发指定的 result.md 写明 changed files、具体测试/构建结果、安装包路径及尚需真机验证的项目。构建通过不代表手机系统已显示位置访问或视频已嵌入 GPS。

## D. Codex 中途复核项（完成前一并处理）

- 不要因为 GNSS 状态或 NMEA 某一个附加回调注册返回 false 就在 requestLocationUpdates 之前退出。现有实现支持 Android MSL 海拔与 NMEA/GNSS 互补，额外订阅失败应诊断并保留可用数据路径，仍需发起真实 GPS 定位。不能把本次“无位置访问”变成新的提前退出问题；绝不伪造缺失测量。
- PhoneGps 的订阅成功、首个真实 fix 和启动失败应有不含坐标的简短诊断。shutdown 会清空注册标志，错误原因必须在清理前保存。
- 四个状态格应显式居中，并检查圆屏边界：44px 图标在顶边应完整显示，避免默认 flex 方向和未居中导致左右图标裁切。主容器和工具页背景也明确为 #000000。
- 需要验证运行中打开/关闭/重开 GPS 确实调用定位启停，且不会重建 BLE。补充实际生命周期路径验证与编译后主页面结构检查，不能仅凭旧协议测试通过就宣告本轮回归覆盖。
