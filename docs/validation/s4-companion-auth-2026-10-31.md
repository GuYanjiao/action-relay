# S4 配套信道与板载指示灯实机记录

## 已通过的云端检查

- `6fb6c9d` / [41184345148](https://github.com/GuYanjiao/action-relay/actions/runs/41184345148)：共享连接、FE95 发现/读取/订阅/取消/背压回归、NCS/ANT ARM 构建与 UF2 检查全部成功。
- `88cd819` / [41185031728](https://github.com/GuYanjiao/action-relay/actions/runs/41185031728)：增加实际 SAR→L2→认证线程接线、完整合成挑战、错误签名拒绝、代次隔离和密钥清理，全部原生检查、ARM 构建及 UF2 检查成功。
- `167bd8a` / [41185942985](https://github.com/GuYanjiao/action-relay/actions/runs/41185942985)：增加 LED status/off/dim/invert，全部检查与组合构建成功。

这些运行没有本地编译，不含真实用户密钥，也没有相机控制实现。

## 实际刷写与运行

已核对 nice!nano 启动盘、云端摘要和分区后刷入 88cd819；988 个 UF2 块，应用写入 `[0x26000,0x63c00)`，初始栈 `0x2000f900`、复位入口 `0x3a889`。产物 SHA-256：

```text
f9731beaf2b6481aad9e6b90886adccf5857a6a097e807ae9332829d90111a2f
```

刷前 CURRENT.UF2 在仓库外再次备份并核对摘要，目录 `nrf52840-before-auth-20261031-163751`；USB COM6 返回正常。之后按同样流程更新至已通过的 167bd8a，另存 `nrf52840-before-auth-20261031-165143`，USB 仍正常。

原心率工程未改写。此次没有再次验收真实码表 ANT+ 转发，因此保留 ANT+ 代码、固件启动与 ANT+ 实际转发仍是三件不同的事。

## 指示灯结论

程序报告使用 P0.15、PWM 设备就绪，运行约 25 秒记录 2 次弱亮脉冲，与 10 秒周期一致。发送 led off 后，串口确认 mode=off；用户明确观察到“蓝灯仍闪，红灯熄灭”。

因此，本机 GPIO 用户灯为红灯，蓝灯不受此 PWM 控制，最初将其当作可编程蓝灯的假设不成立。这类红板有[蓝色充电指示灯、红色用户灯的版本](https://github.com/joric/nrfmicro/wiki/Alternatives)。目前不能宣称蓝灯频率或亮度已降低；不通过修改 Bootloader、随机 GPIO 或充电电路来处理。红灯暂时保持关闭。

## 蓝牙结果与密钥边界

当前能扫描到目标名称广播。命名候选未识别到 HRS/FE95 广播字段；附近另有匿名 FE95 候选，不能仅凭这一服务就视作用户手表并连接。暂未验证其广播是否可连接。

针对已知名称目标的连接在 3 秒默认窗口触发 HCI 0x02（创建超时）。用户关闭手机蓝牙、重新开关心率共享并停留页面后，结果仍相同。没有建立 GATT 通道，也没有发送用户 Key；这不是密码验证失败。

已另行提交 10 秒连接创建窗口、候选广播 type 日志和 watch status，云端/实机结果待记录。延长窗口用于检查 ANT 搜索共存影响，不能据此认定 ANT 是超时原因。

用户主动提供了自己的 Key 并授权本机测试。临时副本只放仓库外、限制当前账户访问，USB 工具不会回显或保存原始认证日志。未将真实 Key 写入代码、提交、云端 Secrets 或产物；测试结束清除这份临时副本，不重置手表或更改原有绑定。

## 后续更新（2026-11-01）

- b9af27b / [41186332575](https://github.com/GuYanjiao/action-relay/actions/runs/41186332575)、7e7843f / [41187528836](https://github.com/GuYanjiao/action-relay/actions/runs/41187528836) 全部原生检查、ARM 构建及 UF2 检查成功；均已校验后刷入，最后备份为仓库外 nrf52840-before-auth-20261031-171615。
- 完整扫描找到身份后缀匹配的可连接 HRS 候选并连接成功，随后 FE95 适配器返回 -2。该返回值可来自服务、所需特征或 CCC 缺失，现有日志不能确定阶段；没有发送真实 Key。这不同于此前非连接广播造成的 HCI 创建超时。
- 11月1日重新连接 USB 后，12秒全扫描未看到目标广播，上次核实的私有地址单次建连超时。只连接已核实的目标，没有连接附近匿名 FE95 设备。原始扫描、地址和服务日志均留仓库外。
- 增加通道关闭阶段、已发现句柄与读取/订阅 ATT 错误元数据；不打印能力字段内容、身份、密钥、认证数据或应用负载。新增代码待云端检查，不把它记作已部署或互通完成。

### CCC 阶段实机定位

6c14c4b / [41271649659](https://github.com/GuYanjiao/action-relay/actions/runs/41271649659) 全部云端检查通过。核对产物、应用分区并备份到仓库外 nrf52840-before-auth-20261101-120946 后刷入，USB 正常，红灯关闭。

用户重新启动心率广播后已连接，并重新发现 FE95、HRS 及0050/005E/005F。新增日志确认关闭阶段为 ccc，service=0x0013..0x001a、flag=0x0014、rx=0x0016、tx=0x0019、ccc=0。这定位为 Find Information 路径没有找到 CCC，并非 FE95 服务不存在或认证拒绝。

增加一次标准描述符 Read By Type 查询，仍限定在已发现的接收特征与下一特征之间；两条路径都查不到时终止，不硬编码 CCC、不盲订阅。Zephyr 的 [GATT 实现](https://github.com/zephyrproject-rtos/zephyr/blob/v4.3.0/subsys/bluetooth/host/gatt.c) 将 BT_GATT_DISCOVER_STD_CHAR_DESC 映射到 Read By Type。增加备用查询成功和两条查询均缺失的云端回归，代码和实机结果待后续确认。
