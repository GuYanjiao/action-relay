# GPS 与手表界面：构建后复核补齐

前一 Copilot 任务 `20261026-124121-e85e595e` 已完成并释放代码写入权。本轮接手现有未提交改动，仅处理下列复核项，保持版本 0.3.1/4。不重做已完成的 GPS 开关接线和界面精简。

1. **移除新增的提前退出**：PhoneGps.start() 当前新增 `if(!gnssRegistered||!nmeaRegistered){shutdown();...return false;}`。它会在 NMEA 不可用时再次导致完全不发起 GPS 位置订阅。Android MSL 海拔与现有 NMEA/GNSS 卫星数有互补路径，不应强制两个附加监听都注册成功。附加监听失败时保留真实 GPS_PROVIDER requestLocationUpdates，完整数据不足则保持 waiting_quality，绝不伪造。清理前保存错误原因，不能在 shutdown 重置标志后读取它们来判断哪项失败。
2. **诊断尚未实际实现**：上一轮 result 称已增加订阅/首个 fix 诊断，但 PhoneGps 仅新增了提前退出，没有实际成功/首个 fix 的诊断。补上不含位置坐标的简短日志，清楚记录订阅成功、拒绝/异常和首个真实 GPS fix。首个 fix 只记录一次，状态变化去重，避免每秒刷屏。
3. **居中与圆屏安全区**：status-item 只有 align-items，没有显式布局方向/justify-content；图标可能靠左。44px 四个图标要明确居中，并适当下移顶行或收窄列间距，保证所有图标四角在 466px 圆形安全区内。remote 与 tools 明确 #000000。GPS permission/unavailable 等错误状态映射到红色 error 图标，不能继续全部显示为搜索中。
4. **有意义的回归覆盖缺失**：当前两个 watch/tests 文件仍未修改，也没有 GPS 生命周期验证。请补齐：运行中的中转打开 GPS、重复重配置、关闭再开启、启动失败可重试、停止中转清理；不得只检查源码字符串或只重跑旧编码测试。允许抽取最小且实际接入的纯 Java 生命周期协调器，用假定位驱动运行启停序列；测试也要纳入 scripts/test.ps1。编译后页面测试需要验证真实模板没有标题/绿点/图标标签/capture-label，四个状态图标仍绑定且录制切换正常。
5. 正确区分界面中的 GPS 已关闭、搜星/等待完整测量、权限不足、定位服务关闭和启动失败。继续保持 FGS 成功提升后才订阅、GPS 指令低优先级与真实完整测量要求。

修改后重新全套测试、成对构建与签名/版本校验，更新 COPILOT_STATUS.md 和本次 result.md，明确没有真机证明。不要改 AGENTS.md、派发脚本、本文、旧清单，不提交/推送，不发布。Codex 复核后完成 Git 同步。
