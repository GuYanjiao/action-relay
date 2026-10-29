# 阶段 2A：认证计算内核

`miwear_auth.c` 仅实现 MiWear 的 HKDF-SHA256 会话材料派生、手表 HMAC 校验和主机证明计算。HMAC 由外部密码库提供；没有自制 SHA/AES，也不含真实 AuthKey。

算法依据为已记录的 AstroBox Core `auth.rs` 固定提交，代码按标准 HKDF 与协议字段独立表达，没有复制上游 Rust 实现。云端使用 Python hashlib/hmac 生成公开人工向量，C 测试以 OpenSSL 为后端验证派生方向、签名顺序、每字节篡改拒绝、随机数变化和后端失败。

该模块尚未接入 BLE、SAR/Protobuf、AES-CCM 及完整认证状态机，也没有成为“认证成功”的判断依据。C 文件会随诊断固件编译以核对 ARM 可编译性；不会从诊断命令调用，不会在板上读取或存储用户密钥。

`tests/vectors.h` 由 CI 生成，不提交。下一步要依据真实 FE95 服务与合法的用户设备凭据完成认证封包和消息往返。
