# 仅云端构建与固定安装包签名

基于 c7a017a。用户最新要求：以后不再本地编译，只在 GitHub Actions 编译，并配置固定云端签名。Codex 负责加密配置 Actions Secrets 与最终 Git 签名/推送；Copilot 只修改下面列出的工程文件。

## 必须遵守

- 不在本机调用 javac、aiot、build.ps1、build-local.ps1 或会触发 Java 编译的 test.ps1；不生成本地 APK/RPK。只做脚本/JSON/JS 静态语法检查与差异检查。真正构建和回归由云端执行。
- 不访问本机 SoftwareSigning 的 private/、.signing/、watch/sign/，不读取/复制/输出任何密钥或口令。Codex 单独处理加密上传；无需帮用户“导出”本地私钥。
- 不改 AGENTS.md、派发脚本、本文或旧检查清单。Codex 已在 AGENTS.md 更新最新用户偏好。
- 保持应用功能及界面不变。两端版本升为 0.3.2 / versionCode 5，用于区分固定发布签名构建。

## 固定签名的契约

- GitHub Actions Secrets 由 Codex 配置：ACTION_RELAY_KEYSTORE_BASE64（PKCS12 的 Base64）、ACTION_RELAY_STORE_PASSWORD、ACTION_RELAY_KEY_PASSWORD。
- 固定别名：guyanjiao-release；固定证书 SHA-256（公开指纹）：6CC93023797565C425CA5771E27F64692CA1FB934018075A4DB6612B35FEE0C0。
- 用公开配置文件（例如 signing/release-signing.json）保存别名与预期指纹。APK 与 RPK 都必须来自这一个 PKCS12 的同一条目。
- 缺少任一必要 secret、密钥文件或别名时失败；禁止自动生成临时密钥、默认 android 密码或回退至项目旧测试证书。

## 修改范围与实现

1. .github/workflows/build.yml：在云 runner 解码 keystore 到 RUNNER_TEMP 的专用目录，构建时显式传入路径/别名，口令仅使用 env，不打印值。Secret 存在性应尽早检查，实际恢复在 npm 安装后、签名前进行。只给需要的步骤暴露 secrets；不要输出 shell trace。
2. build.ps1 / ExportSigning.java：去掉 keytool 自动生成和默认密码；支持传入 keystore/别名，读取环境中的 store/key 密码。ExportSigning 的口令不能作为命令行参数；apksigner 用 env: 引用口令。向 watch/sign/debug 导出的材料仅用于云端构建，不上传。预期证书指纹应在产生最终可交付安装包前核对。
3. verify-pair.mjs：比较导出证书与固定指纹，核对两端同证书与版本，输出公开指纹及包哈希，可将检查结果保存为 artifact 内的 signing-report.json。不把仅仅“碰巧含有证书字节”描述为 APK 密码学验证；APK 仍由 apksigner verify 验证。
4. 云端构建后运行完整 scripts/test.ps1（包括 GPS 生命周期、GPS 编码与编译后手表模板）、verify-pair 和包签名校验，再上传精确指定的 APK、RPK 和公开签名报告。禁止上传通配整个仓库/签名目录或私钥。always 清理 runner 中 keystore 以及导出的 private.pem，清理时校验路径在本 job 的 RUNNER_TEMP/明确的 watch/sign/debug 下，不跨 shell 拼接删除命令。
5. scripts/build-local.ps1 改为明确禁用本地安装包构建的提示，不再调用编译器。移除 .vscode/tasks.json 默认本地编译/Java 测试入口；可改为打开 Actions 构建页面，不用引入 gh 依赖。同步 README、docs/copilot-workflow.md、.github/copilot-instructions.md 说明“本地编辑静态检查→签名提交推送→云端构建与回归→下载固定签名安装包”。旧交接单是历史，不能覆盖新偏好。
6. 构建脚本与校验器尽量从 manifest 读取版本，避免新版本漏改硬编码。workflow 可以读取 manifest 写入 GITHUB_ENV，同时验证 Android/Watch 版本一致。

## 验收与回报

- 本地仅静态检查；不要为了证明构建而自行本地运行编译。
- result.md 写清修改文件、静态检查结果、所需 secret 名称与云端验证步骤，诚实标明“等待 Codex 推送后云端编译”，不要声称已完成云端验证。
- 不 git commit/push/merge、不得发布 Release。Codex 将以用户 GPG 密钥签名提交并推送，观察云端构建，再检查签名报告指纹。
- 文档说明首次由旧临时签名迁移到固定发布证书需重新安装两端，后续版本固定签名更新。不要写入口令、私钥或 Base64 材料。

参考：[GitHub Actions Secrets](https://docs.github.com/en/actions/how-tos/write-workflows/choose-what-workflows-do/use-secrets)。
