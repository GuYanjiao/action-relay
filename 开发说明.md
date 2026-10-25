# 开发与复现

## 工程

`android/src` 是原生 Java 手机应用；`android/res` 是资源；`android/libs` 是从小米官方示例提取的 XMS SDK 1.4。`watch/src` 是 Vela 快应用；`watch/tests` 包含控制器和编译后页面测试。`tests/protocol` 是大疆协议测试；`tests/connect_cmd_frame.txt` 是大疆官方测试报文。

手机使用小米官方 Node / Auth / Message / Service API，通过小米运动健康访问手表。通信权限为 `DEVICE_MANAGER`。手机与相机使用 BLE 的 FFF0 服务、FFF4 通知和 FFF5 写入。所有控制与回调汇总到主线程。

## 消息契约

手表发送：`{v:1,type:"hello"}`、`{v:1,type:"getStatus"}`，或 `{v:1,type:"command",id,action:"start"|"stop",session,ticket}`。

手机回传 `status`，包含 `camera`、`recording`（布尔值或 null）、`canControl`、`busy`、`recordSeconds`、`battery`、`message`、`session`、`ticket`。`result` 包含命令 id、ok、phase 和 message；phase 可以是 accepted、confirmed、failed、unknown 或 rejected。

手机保留短效 ticket 的本地发出时间，超过 6 秒拒绝；连接变化会更换 session。请求 id 去重，录像命令不重试。蓝牙发送队列的报文等待超过 4 秒就放弃并断开；相机状态超过 3 秒不再允许控制。未知相机状态传 null。

## 构建

需要 Windows、JDK 17、Node.js、Android SDK Platform 36 与 Build Tools 36.0.0。进入 watch 目录安装 package.json 中的依赖，再回工程根目录：

```powershell
cd watch
npm install
cd ..
.\build.ps1 -AndroidPlatform "$env:ANDROID_SDK_ROOT\platforms\android-36" -BuildTools "$env:ANDROID_SDK_ROOT\build-tools\36.0.0" -JdkBin "$env:JAVA_HOME\bin" -AiotCli "$PWD\watch\node_modules\aiot-toolkit\lib\bin.js"
```

脚本使用 Android 原生工具构建 APK，并生成同一套个人测试证书为两端签名。输出在 `android/build/ActionRelay-0.2.0.apk` 与 `watch/dist/cn.local.actionrelay.debug.0.2.0.rpk`。

源码包不包含交付安装包的签名私钥。自行构建会生成新证书，无法直接覆盖旧签名的 APK；需要卸载原两端应用，再成对安装并重新授权。`.signing/` 和 `watch/sign/` 不应公开分享。

```powershell
node watch/tests/controller.test.mjs
node watch/tests/compiled.test.mjs
javac --release 8 -encoding UTF-8 -d test-classes android/src/cn/local/actionrelay/protocol/*.java tests/protocol/cn/local/actionrelay/protocol/DjiProtocolSelfTest.java
java -cp test-classes cn.local.actionrelay.protocol.DjiProtocolSelfTest tests/connect_cmd_frame.txt
```

`verify-pair.mjs` 需要先 `npm install --no-save jszip`，再执行 `node verify-pair.mjs`，检查包结构和同证书携带情况。APK 的正式签名验证由 build.ps1 中的 apksigner 执行。

## 参考

- [小米 Interconnect](https://iot.mi.com/vela/quickapp/zh/features/network/interconnect.html)
- [小米官方 Android 接入示例](https://cdn.cnbj3-fusion.fds.api.mi-img.com/quickapp-vela/interconnect_dev_test_demo.zip)
- [大疆官方协议示例](https://github.com/dji-sdk/Osmo-GPS-Controller-Demo)，参考提交 `92fe23e5a749f189593f980a26a105c3bb66aa1c`。
- [Android connectedDevice 前台服务要求](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device)

保留 `THIRD_PARTY_NOTICES.txt`；小米 SDK 的来源和限制见 android/libs/README.md。官方旧接入 PDF 中针对旧款设备的测试固件升级流程不适用于本项目的 S4 安装步骤。
