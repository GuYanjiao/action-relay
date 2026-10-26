# 开发文档

## 技术边界

Action Relay 由两部分组成：

- `android/`：原生 Android Java 手机端，负责 DJI BLE 会话、手机 GNSS、
  手机 UI、手表互联和快捷设置磁贴。
- `watch/`：Xiaomi Vela JavaScript/UX 手表端，负责圆屏控制界面、状态呈现
  和与手机的互联消息。

Android 字节码目标使用 `javac --release 8`；这表示字节码兼容目标，不表示
必须使用旧版 JDK。Android `targetSdk` 保持 36，以维持现有权限和运行行为
契约。手表包的版本与 Android manifest 保持 `0.4.1` / versionCode `10`。

## 云端构建与签名

构建入口是 [.github/workflows/build.yml](../.github/workflows/build.yml)。
GitHub Actions 在 Windows runner 上恢复依赖，构建 APK 和 RPK，运行回归，
验证 APK 的密码学签名，并检查两端版本与包配对。固定发布证书只通过 Actions
Secrets 提供；需要的 Secret 名称是：

- `ACTION_RELAY_KEYSTORE_BASE64`
- `ACTION_RELAY_STORE_PASSWORD`
- `ACTION_RELAY_KEY_PASSWORD`

工作流会核对公开的
[signing/release-signing.json](../signing/release-signing.json) 中的证书指纹，
缺少签名输入或指纹不匹配时失败，不生成临时发布证书。签名材料在 runner
清理，不进入仓库、日志或构建附件；Git 提交签名与 APK/RPK 签名是两套独立
机制。

### 工具链口径

以下为 2026-10-26 核实的稳定工具链配置：

| 工具 | 配置版本 |
|---|---|
| Node.js | 26.12.1 |
| Temurin JDK | 27 |
| Android Platform | 37.2 |
| Android Build Tools | 37.0.0 |
| `aiot-toolkit` | 2.0.5 |
| `@aiot-toolkit/jsc` | 1.0.10 |
| JSZip（包校验） | 3.10.2 |
| Android Command-line Tools | 23.0（16111833） |

Node/JDK 版本分别由根目录 `.node-version`、`.java-version` 指定；实际使用版本以
[GitHub Actions 工作流](../.github/workflows)和
[watch/package-lock.json](../watch/package-lock.json)为准。若云端兼容性要求
调整版本，以实际配置和云端结果为准，不将“最新”作为兼容性承诺。

## 测试入口与目录

所有可执行构建和回归在云端运行：

- [ci.yml](../.github/workflows/ci.yml) 运行手表 Node 测试和协议 Java 测试。
- [scripts/test.ps1](../scripts/test.ps1) 是完整回归入口，按 `all`、
  `protocol` 或 `watch` 选择范围。
- `tests/protocol/`、`tests/lifecycle/` 和 `tests/transport/`
  包含协议、GPS、队列和传输测试。
- `watch/tests/` 包含手表控制器及编译后页面测试。
- [build.yml](../.github/workflows/build.yml) 负责成对打包、签名验证和上传
  同次构建的 APK/RPK。

本地允许编辑、阅读、搜索和静态检查；按照项目约定，不调用 Android、Vela
或 Java 编译器，不运行本地回归，也不执行本地打包。云端测试通过不替代
Watch S4、Android 手机和 Action 4 的硬件验证。

## 消息契约与状态真实性

手表通过 Xiaomi 设备互联把控制消息发送给手机，手机再通过 DJI BLE 协议
与相机通信。控制、状态和 GPS 是不同类别的消息，不能把传输层的“已受理”
当作相机已经执行：

1. **传输受理**：手机或手表确认消息进入发送路径。
2. **相机状态**：由相机回报当前录像状态或终态。
3. **用户确认**：手表和手机根据相机回报显示开始、停止、等待、超时或未知。

开始和停止必须保持为明确的独立操作。停止请求可以取代尚未确认的开始，
但不能盲目切换快门，也不能因为未知或过渡状态自动重放开始命令。旧连接、
旧会话和迟到回报必须隔离；结果队列应有界并优先控制消息和终态，GPS/状态
采样应合并旧值，避免无界增长。

GPS 只发送手机真实测量值，并保留新鲜度、质量和完整性判断。权限不足、定位
关闭、首次定位等待、测量不完整、过期或启动失败都必须以明确状态呈现；
收到一个位置点不代表相机已经收到满足协议要求的完整 GPS 数据帧。硬件验收
应分别记录相机停止与保存、手机-手表通信、手机 GNSS 质量及视频 GPS 数据。

手机端 UI 不承载重复的录像主控，避免误触和不必要的自定义绘制；录像状态以紧凑文本呈现，
控制入口保留在手表和快捷磁贴。蓝牙扫描候选设备使用有界集合，避免扫描回调异常时持续
占用内存；控制、结果、日志和 GPS 队列同样必须保持有界或合并旧值。

手机和手表状态栏的顺序固定为手机、相机、GPS、电量；电量颜色统一为低电量红色、
中等电量黄色、高电量绿色，未知电量使用灰色。手表录制按钮居中，开始支持轻触，
停止必须长按，已录制时间显示在按钮下方。录制反馈使用官方
`@system.vibrator.vibrate` 接口：开始录像为短振，按下停止键进入长按时立即长振；设置页
可关闭并通过 `@system.storage` 持久化。官方文档列出的 `vibrator.start/stop`
与系统默认振动模式不支持 Xiaomi Watch S4。当前公开接口目录也没有目标设备可
验证的声音播放接口，因此 0.4.0 不加入声音调用，避免使用未公开或不兼容的模块。

## 变更入口

修改 Android 代码前阅读 [AGENTS.md](../AGENTS.md)，并保持协议、状态机、
队列和 UI 的语义一致。任务清单保存在
[docs/copilot-checklists](copilot-checklists)，第三方许可见
[THIRD_PARTY_NOTICES.txt](../THIRD_PARTY_NOTICES.txt)。不要在文档或日志中
加入私钥、口令、个人设备标识、个人位置轨迹或原始个人日志。

构建使用 Android 官方 AAPT2、D8、zipalign 和 apksigner；更新工具链不需要引入另一个并行的 Gradle 工程。根目录和 watch/ 各有锁文件，分别通过 `npm ci --ignore-scripts` 和 `npm ci` 恢复校验工具及官方手表工具链，避免在构建中临时解析未锁定的 JSZip。`aiot-toolkit` 保持官方 latest 稳定版 2.0.5，不使用预发布标签。

打包脚本会在云端构建开始前清理旧的 Android/手表生成目录，构建成功后仅保留最终
APK/RPK，删除 dex、classes、资源中间包和手表签名工作目录；手表编译目录会保留到
云端回归与配对校验结束，因为编译后页面测试需要读取页面输出，配对校验需要读取公开证书。
手表私钥由工作流的最终清理步骤删除。因此旧版本包不会混入
工作区或上传物。正式 APK/RPK 体积应与项目源码、依赖安装目录和本地缓存分开统计。

维护记录：[项目状态](maintainers/status.md)、[云端验收](validation/)、[停止确认机制](testing/stop-confirmation.md)。早期检查清单属于历史任务记录，当前目录与执行方式以本文及 AGENTS.md 为准。

本轮 Vela 资料来源：[振动 vibrator](https://iot.mi.com/vela/quickapp/zh/features/system/vibrator.html)、
[数据存储 storage](https://iot.mi.com/vela/quickapp/zh/features/data/storage.html)、
[官方 JS 应用接口目录](https://iot.mi.com/vela/quickapp/zh/features/)。
