# 检查更新与视频/拍照模式控制验收

日期：2026-11-22。仅涉及手机中转仓库，开发板仓库及原心率ANT+项目未修改。

## 实现

- 手机“工具 → 检查更新”读取GitHub正式Release，按数字比较版本，显示日期和说明，提供同版APK/RPK下载与发布页入口。处理限流、网络超时、无正式版本和异常数据；失败不显示“已是最新版本”。
- 检查由用户手动触发，与中转服务分离，没有后台轮询或额外定时任务；Activity销毁时关闭连接及工作线程。
- 手表底部规格入口和手机相机页支持普通视频/拍照模式选择。只发送公开1D04模式命令，匹配ACK之后再观察目标模式待机状态才确认；选择模式本身不发送快门。待确认时隔离拍录和重复切换，超时/断线不自动重放。
- 分辨率、帧率仍只读；重新核对的node-osmo配置属于直播输出，不是本地录像规格接口。S4系统卡片仍缺少提供者注册/渲染契约，Gadgetbridge已知卡片布局接口不能独立创建新卡片。
- 删除manifest中重复的extractNativeLibs声明，由既有Gradle `jniLibs.useLegacyPackaging=false`控制，保留未压缩原生库与16KB要求。

## 个人签名与阶段保存

用户于2026-11-21批准使用现有Actions Secrets中的原GPG密钥签署正式代码提交，允许临时分支无个人签名引导。引导提交及临时工作流不进入main；最终代码提交直接以原main为父提交。

[签名运行44976789240](https://github.com/GuYanjiao/action-relay/actions/runs/44976789240)成功生成正式提交`c36ceba0c75b16e65a241a11c8da3247d4988539`。本地验证为有效个人GPG签名，指纹`9BC5BCD68F629DCE8438F9A15B12374378EAC33B`。正式树与引导树仅差移除临时工作流；通过预览后快进main。私钥和口令未从Actions导出。

## 云端回归与预览

[预览构建44976801900](https://github.com/GuYanjiao/action-relay/actions/runs/44976801900)的Rust/JNI与整包任务全部成功：

- Rust的3项单元测试及Clippy；JNI随机CRC/帧对照、无效输入检查。
- 22项更新检查：数字版本顺序、当前/较旧版本、正式Release数据、同版下载匹配、缺失附件、异常JSON和版本号等。
- 21项显式模式控制检查：ACK/状态时序、无快门副作用、待确认隔离、回放请求、外部拍摄、拒绝、超时与断线。
- 既有43项协议、48项遥测、36项回放/拍照、55项停止转换，以及重连、队列优先级和GPS生命周期检查。
- 手表控制器、长按进度、圆屏布局、遥测显示，以及编译后的页面事件/生命周期和模式选择页面测试。
- APK/RPK构建、固定证书校验和整包配对验证；全部原生库ARM64及16KB ELF对齐检查。

首次预览44922213292因编译页面测试中的旧拍照模式显示断言失败。修正后保留恢复页面时清除旧状态的检查，最终预览全部通过；未跳过失败测试。

## 正式发布

[自动发布44977354655](https://github.com/GuYanjiao/action-relay/actions/runs/44977354655)的plan、核心回归、整包构建和publish任务全部成功，公开发布[v0.4.9](https://github.com/GuYanjiao/action-relay/releases/tag/v0.4.9) / versionCode18。发布源码为上述c36ceba；Git标签由同一原GPG密钥签署，本地`git verify-tag v0.4.9`验证通过，指向该源码。

从公开Release下载了APK、RPK、build-info.json、package-verification.json和SHA256SUMS.txt，全部匹配GitHub资产摘要；安装包另匹配验证报告及SHA256SUMS。两包ZIP完整，RPK manifest确认包名及0.4.9 / 18，build-info指向c36ceba和本次正式云端运行。固定安装证书指纹仍为`6CC93023797565C425CA5771E27F64692CA1FB934018075A4DB6612B35FEE0C0`。

| 安装包 | 字节数 | SHA-256 |
| --- | ---: | --- |
| ActionRelay-0.4.9.apk | 2,742,912 | a95c5284ad9e58c0bec28879e516f197508c03d9a00d652da5d89743d95b8a2c |
| ActionRelay-Watch-0.4.9.rpk | 172,633 | 7bea849598bc126ce7291de44b4563d167b5daf7e7a59f432d447c8cbaf3c743 |

APK仅有ARM64 Rust核心及AndroidX graphics-path原生库。逐库摘要与报告一致，ELF加载段均满足16KB对齐，ZIP中均不压缩；数据偏移分别2,195,456和2,506,752，均为16KB整数倍。公开附件含两安装包及三个追溯/验证文件。

## 设备边界

本地仅进行编辑、静态解析、Git签名验证及公开产物检查，所有编译与可执行回归均在GitHub Actions。

新增更新页面的实际网络/下载体验、Action4视频/拍照切换、回放返回和文件保存仍需真机验证。云端模拟回报证明控制逻辑满足测试场景，不代表目标固件已实测互通。精确录像规格和S4系统卡片未实现；具体缺口已记录，未伪造接口。没有新增功耗或延迟实测结论。
