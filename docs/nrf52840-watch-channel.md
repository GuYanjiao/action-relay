# nRF52840 手表应用信道（实验分支）

手表仍使用 `@system.interconnect`。开发板通过 FE95 模拟配套端，认证后把应用消息中的 JSON 交给同一套手表控制器，无需调用 S4 未开放的 BLE 扫描接口。分支 `work/nrf52840-watch` 保留手机中转兼容性和现有应用身份。

## 连接与识别

手表进入前台发送 `{"v":1,"type":"hello"}`。开发板完成认证并建立本应用消息通道后回复：

```json
{"v":1,"type":"relayHello","session":"boot-unique-session","relay":{"kind":"nrf52840"}}
```

session 每次启动或重建认证通道生成，不能复用，长度 1–128；不要使用设备地址或 AuthKey。手机旧版本无需增加字段，没有 relay 的新会话按手机处理，同一会话后续消息可省略 relay。明确提供 relay 时仅接受 phone / nrf52840，同一 session 不允许换身份。

收到 relayHello 会点亮开发板图标，但不会启用拍摄。切换会话取消待确认指令并清空旧相机状态，最近 8 个旧会话的迟到消息被丢弃。同会话重复握手保留已接收的有效状态。

## 信道探测

开发板握手后手表自动发送一次 ping；点击连接页第一行或重新打开连接页也可探测。仅允许一条探测在途，2 秒到期后丢弃迟到结果，没有后台循环探测。

```json
{"v":1,"type":"ping","session":"boot-unique-session","id":"watch-generated-id"}
{"v":1,"type":"pong","session":"boot-unique-session","id":"watch-generated-id"}
```

开发板回送相同 id 和当前 session。往返耗时仅显示在连接页，表示应用消息通道延迟，不代表相机响应延迟。pong 不更新相机状态，也不确认指令。退出页面取消待测记录。

## 相机控制沿用 v1

握手后手表请求 getStatus。开发板必须回报真实相机状态，例如：

```json
{"v":1,"type":"status","session":"boot-unique-session","relay":{"kind":"nrf52840"},"seq":1,"revision":1,"ticket":"fresh-command-ticket","camera":"ready","recording":false,"canControl":true,"canStart":true,"canStop":false,"canPhoto":false,"busy":false,"gpsEnabled":false,"gpsState":"off"}
```

开发板 seq / revision 必须是非负安全整数；session / ticket 必须有效。同会话 seq 严格递增，状态 6 秒失效。未连接相机时使用 camera=disconnected、recording=null，并关闭 canControl/canStart/canStop/canPhoto，不能使用示例 ready 值冒充实测。

command 携带 id / action / session / ticket。accepted 只表示受理，相机真实确认后才回 confirmed。设备侧还必须验证 ticket、去重 id、拒绝过期会话，超时不能重放快门。结果可携带同会话且更新的 status，省去一次查询。

当前板无 GNSS，手表对 nrf52840 会话固定关闭 GPS 图标，避免沿用旧手机定位。温度、剩余录制秒数和规格沿用现有字段，不存在的数据省略。

## 验证边界

FE95 与 HRS 同时存在已实测。认证计算、SAR 和组合固件已通过云端检查；实际 AuthKey 认证、应用消息转发、Action 4 控制仍待接入和实机验证。手表端回归检查身份切换、失效状态、迟到结果、探测超时、前后台切换及手机兼容性，全部通过云端执行。
