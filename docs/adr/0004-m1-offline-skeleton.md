# ADR-0004：M1 离线骨架的实现选择（mock 适配器用 Node、线上协议、已知偏差）

- 状态：已接受（2026-11-07）
- 关联：设计文档 §5.2、§5.3、§6、§9、§10、§11 M1、§14.3–14.5；ADR-0003

## 1. mock 适配器用 Node，而不是 Java

设计 §14.3 规定适配器是**独立进程**、经 Adapter Protocol 与 Java 通信，平台不在 JVM 内加载适配器代码；
§3 / §14.2 / ADR-0003 规定首版适配器驱动是 Node，应用内载荷必须是 JS。M1 的 mock 适配器因此选 Node：

- 它验证的是 M3 真正要用的那条边界：**Java ↔ 独立 Node 进程**的认证握手、帧 MAC、fence、codec 跨语言一致性。
  若用 Java 写 mock（甚至进程内 stub），这些跨进程、跨语言的契约都测不到。
- `sdk/driver-node/adapter-runtime.mjs` 即首版 MMAB Adapter SDK（Node），mock 只是其上最薄的一层；千问 / Codex
  参考适配器在 M3 复用同一 SDK。
- `sdk/web/codec.mjs` 同时用于浏览器 shim 与 Node 适配器，Java 侧 `StructuredCodec` 用同一组 fixture 做契约测试。
- 代价：`mvn verify` 的端到端测试需要本机有 Node（`-Dmmab.node=` 可指定路径）。零 npm 依赖。

## 2. Adapter Protocol v1 线上格式（M1 定稿为草案 v1）

- 服务密钥：每次启动 32 字节随机值，经 **stdin** 传入后立即关闭 stdin；不进 argv / 环境变量 / 日志（§6.2）。
- 适配器只监听 127.0.0.1 随机端口；连接后必须在 3 秒内完成**双向** HMAC-SHA256 握手（绑定双方 nonce、
  instanceId、generation、协议版本），否则 `{"t":"error"}` 并断开。来自 loopback 不授予任何权限。
- 认证后每帧 `hex(HMAC(sessionKey, 方向\njson)) + " " + json`，json 内计数 `n` 严格 +1：重放、乱序、反射、
  跨连接拼接都会断开连接。
- fence 由平台用 `CONTROL mmab.fence` 推送；适配器自己拒绝旧 generation / 旧租约 / 无租约 / 过期 deadline /
  未实现的操作。规范：`sdk/protocol/adapter-wire-v1.md`；跨语言固定向量：`sdk/protocol/fixtures/wire-v1-vector.json`。

## 3. 与设计的已知偏差（M1 有意为之，后续里程碑处理）

| 设计要求 | M1 实际 | 去向 |
|---|---|---|
| Spring Security WebFlux（§3） | 未引入；安全规则集中在显式 `GuardFilter`（每条规则都有测试） | M2 评审是否迁移；迁移不得放宽任何现有测试 |
| 租约 / 实例元数据用 SQLite（§3、§10.1） | 全部内存：租约、会话、票据。进程重启即全部失效（与 §10.1 "启动时 fence/revoke" 结论一致） | M2 引入 SQLite 存实例 / 审计；租约仍启动即撤销 |
| 操作者口令由 host-owned 本机 TLS 页面初始化（§6.1） | 只接受配置 / 环境变量中的 PBKDF2 hash（`MMAB_OPERATOR_PASSWORD_HASH`）；未配置时登录返回 `SETUP_REQUIRED` | M2 |
| 监听同时绑定 LAN IP 与 127.0.0.1（§5.1） | 所有监听绑定同一个精确地址（`mmab.console.bind-address`），不允许通配；Host / Origin 只接受 `mmab.web.public-hosts` | M3 入口切换前补多地址绑定 |
| 证书体系（§6.3） | 只支持配置 Spring SSL bundle；测试在运行时用 keytool 生成一次性自签证书（不入库） | M3 / M4 |
| MessagePort 中继（§9.4） | `PortTable` 生命周期契约 + 测试已完成；尚未接入浏览器 WS 中继（mock 无端口） | M3（Codex） |
| 浏览器 WS 应用级 credit（§9.3） | 未实现 credit；出站超预算即安全断开该连接，入站在适配器队列高水位时暂停读取 | 视 M4 实测决定 |
| 实例 desired/actual-state 对账、退避（§7） | 只有启动时拉起 + 手动 `restartAdapter`（generation +1）；适配器退出即 `ADAPTER_DOWN`，不自动重启 | M2 / M5 |
| 真实 Chrome、受信证书、实际 LAN IP 下验证跨端口防护（§11.1） | 只用 JDK HttpClient / WebSocket 按浏览器头部模拟；fixture 页面的 shim / app.js 未在真实浏览器执行 | 须在 M4 前补做，不能把 M1 测试当成浏览器验收 |
