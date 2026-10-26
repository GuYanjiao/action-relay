# 0.3.2 云端验收

日期：2026-10-26。验收代码：`1187fd1d0e4d4ef2e1e46c8eebad46187c7adb7e`；后续 README 与交接整理不改变已验收的应用或构建代码。

- [Build packages：成功](https://github.com/GuYanjiao/action-relay/actions/runs/40274376773)
- [Validate：成功](https://github.com/GuYanjiao/action-relay/actions/runs/40274376742)
- 固定签名从 GitHub Actions Secrets 恢复，未使用本机编译结果。APK、RPK 与公开签名报告已作为 artifact 上传。
- APK 密码学验签成功，实际签名证书 SHA-256 与配置相同：`6CC93023797565C425CA5771E27F64692CA1FB934018075A4DB6612B35FEE0C0`。
- RPK 携带相同证书；两端 CRC、版本、包名、Watch feature、APK DEX/Manifest 与包内签名私钥排除检查通过。
- 协议 43 项、停止确认 55 项、GPS 编码/质量 44 项、GPS 生命周期 10 项、发送队列、11 个手表控制器场景以及编译后模板/点击事件检查通过。
- 云端清理 keystore 和导出私钥的步骤成功。

该次云端产物 SHA-256：

| 包 | SHA-256 |
|---|---|
| APK | f2991380450165cd0e3c760a418b8437c1d5754aeaf066e7ae07d11ac5091264 |
| RPK | 34d47df2b27d5cb554292f6f00c94dc35635971c70a038e729ac6d0f12875c98 |

这证明构建、固定签名和自动回归通过，不证明真实手机已获得完整 GNSS、视频已嵌入 GPS，或锁屏停止等硬件行为。
