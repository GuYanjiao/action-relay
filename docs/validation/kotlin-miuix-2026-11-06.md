# Kotlin / Compose / miuix 迁移验收

日期：2026-11-06。范围为手机中转仓库；开发板仓库与原心率 ANT+ 项目未修改。

## 变更范围

- Android 应用的16个 Java 源文件全部迁为 Kotlin，包括蓝牙、GNSS、前台服务、Xiaomi SDK 适配、控制确认状态机和 JNI 调用封装。
- 手机界面使用 Compose 与 miuix-ui 0.9.4 的主题、卡片、按钮和开关；主题跟随系统，保留授权、相机记忆、GPS、日志和开启中转磁贴。
- Rust 协议与队列核心、JNI 导出名、手表消息格式保持兼容。仍只发布 ARM64。
- 引入固定 Gradle Wrapper 9.6.0、AGP 9.5.0、Kotlin / Compose 编译器 2.4.21。应用字节码目标17；Android最低31、target36保持不变。
- Wrapper JAR 的 SHA-256 与 Gradle 官方公布值一致：`497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7`。发行 ZIP 也在 wrapper 配置中固定摘要。

## 云端核心回归

源码8e0f07c的 [云端构建42159527533](https://github.com/GuYanjiao/action-relay/actions/runs/42159527533) 已通过核心任务：

- Rust：3个单元测试、Clippy，Linux JNI 构建及 Android ARM64 交叉构建。
- Kotlin 生产实现 + 原有 Java 测试：3000轮独立 CRC/帧对照；43项协议/会话、48项遥测、36项回放/拍照、55项停止转换和44项 GPS 协议/质量检查。
- 传输优先级、停止覆盖、GPS合并/过期、重连取消与预算、手表结果队列以及 GPS 生命周期/消费者门控全部通过。
- 固定签名识别：10项兼容/拒绝检查通过。

迁移期间由既有测试发现并修正 Kotlin 原始数组空判断、GPS空输入异常类型和新版 JVM 默认方法编译选项问题。未放宽原有断言。

## 整包构建阶段

源码3b45f90的 [构建42160821268](https://github.com/GuYanjiao/action-relay/actions/runs/42160821268) 已通过 Kotlin/Compose 编译、R8、APK/RPK构建、固定签名及手表回归。最后包配对校验仍按旧版单一Rust库断言，因此拒绝了新增的AndroidX图形库；已扩展为逐库校验ARM64/ELF/16KB对齐，并记录各库摘要，待完整重跑。

SDK适配确认 `requestPermission` 返回权限列表，回调后改用 `checkPermission` 核实实际授权；消息回调按SDK的非空字节数组契约实现。

## 正式发布

签名合并提交 `6fc60bb6d866590781f46292572ffdc019b463a1` 的 [正式构建42162515798](https://github.com/GuYanjiao/action-relay/actions/runs/42162515798) 全部成功，自动发布 [v0.4.8](https://github.com/GuYanjiao/action-relay/releases/tag/v0.4.8)，版本码17。

已从公开Release下载APK、RPK及验证报告，文件SHA-256同时匹配GitHub资产摘要与云端报告；读取两包ZIP与RPK manifest确认完整及同版。APK为2,726,496字节，RPK为171,869字节，固定安装证书不变。

```text
APK: 664ac5c5a8ffbb263c812f52fbf16c05f3c46d25495a3b706e3f4fd53c1de412
RPK: 0fa34b663651334618689078a3ced1f4fad764f4b58e63e2a189af623fc932a1
```

APK仅有ARM64的Rust核心与AndroidX graphics-path两库。逐库摘要与报告一致；全部ELF加载段满足16KB对齐，ZIP均为不压缩，数据偏移分别2,179,072与2,490,368，均为16KB整数倍。5个应用/JNI入口均在DEX中。

已fetch并用 `git verify-tag v0.4.8` 验证个人GPG签名，标签指向上述合并提交。开发工具链、Gradle和发布编排已在正式云端完整运行；没有新增用户设备上的运行证据。

## 当前边界

本地仅编辑、静态解析、读取公开产物与验证Git签名；未在本地编译或运行回归。

尚未完成手机上的新界面、JNI、锁屏控制与GPS真机回归，未测量与旧版对照的CPU或电量。因此不宣称Kotlin迁移本身降低功耗或相机确认延迟。
