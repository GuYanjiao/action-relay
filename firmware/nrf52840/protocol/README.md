# 阶段 2A：认证计算内核

`miwear_auth.c` 仅实现 MiWear 的 HKDF-SHA256 会话材料派生、手表 HMAC 校验和主机证明计算。HMAC 由外部密码库提供；没有自制 SHA/AES，也不含真实 AuthKey。

算法依据为已记录的 AstroBox Core `auth.rs` 固定提交，代码按标准 HKDF 与协议字段独立表达，没有复制上游 Rust 实现。云端使用 Python hashlib/hmac 生成公开人工向量，C 测试以 OpenSSL 为后端验证派生方向、签名顺序、每字节篡改拒绝、随机数变化和后端失败。

该模块尚未接入 BLE、SAR/Protobuf、AES-CCM 及完整认证状态机，也没有成为“认证成功”的判断依据。C 文件会随诊断固件编译以核对 ARM 可编译性；不会从诊断命令调用，不会在板上读取或存储用户密钥。

`tests/vectors.h` 由 CI 生成，不提交。下一步要依据真实 FE95 服务与合法的用户设备凭据完成认证封包和消息往返。

## L1 分帧

`miwear_l1.[ch]` 按同一固定提交的 `packet/v2/layer1.rs` 中的线路字段独立实现：A5A5 标记、类型/FRX、序号、长度、CRC-16/ARC 和载荷；不是完整 SAR 状态机。接收流使用固定 1032 字节缓存，接受分拆或合并的 GATT 通知，对坏 CRC、超限长度和非帧字节重新寻找帧头。1024 字节是本 MCU 的初始分配上限，不是对手表协议上限的推断。

调用方必须在断线、会话重置或组帧超时后清空接收状态，回调中不得重入或保留借用的载荷指针。尚无 ACK/NAK、超时重传、SAR 握手、加密或 Protobuf 处理，也未绑定到真实通知。云端测试覆盖固定 CRC 向量、各分拆点、多帧合并、坏帧恢复与边界，并使用地址/未定义行为检查器；ARM 编译单独验证。

## PSA 密码库适配

`miwear_psa.[ch]` 将 HMAC-SHA256 和协议所需的 AES-128-CCM（12 字节 nonce、空 AAD、4 字节 tag）交给 PSA Crypto。每次导入的 key handle 使用默认易失生命周期并销毁，不落盘、不输出密钥。CCM 认证失败时清空可能写出的明文并返回零输出长度；nonce/counter 唯一性仍须由尚未实现的会话层负责。

组合固件启用 Nordic 的 PSA 后端，只初始化密码库；初始化失败不会中止已有 ANT+ 心率流程，且当前没有认证或加密发送命令。云端另以 Linux PSA/Mbed TLS 验证 HMAC 派生向量，并与 OpenSSL 对照不同长度的 CCM 密文和 tag、拒绝各 tag 字节篡改。该测试不是 Nordic 后端在实物上的算法验收，真实设备认证仍待实现。
