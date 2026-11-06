# M1 验收对照：离线协议与安全骨架

范围（设计 §11 M1）：mock 适配器 + 固定 fixture 原版页面，不连接任何真实应用。
证据层级：**全部是离线自动化测试**（`mvn -B clean verify` + `node --test`）。端到端测试起真实 TLS（运行时生成的
一次性自签证书）、多端口监听和两个 Node mock 适配器进程，浏览器一侧用 JDK HttpClient / WebSocket 按浏览器头部模拟。
这不是浏览器验收：设计 §11.1 要求的真实 Chrome + 受信证书 + 实际 LAN IP 验证尚未进行。

| # | 通过标准 | 测试 |
|---|---|---|
| 1 | 开窗票据一次性、30 秒过期、绑定目标端口与 Origin；重放 / 过期 / 错误 Origin / 错误端口被拒 | `LaunchTicketServiceTest`（含 32 线程并发双花只有 1 个成功）；`OfflineEndToEndTest.ticketReplayIsRejected` / `ticketExpiresAfterThirtySeconds` / `ticketIsBoundToConsoleOriginAndTargetPort` |
| 2 | 每端口独立 `__Host-` Cookie 名，HttpOnly / Secure / SameSite=Strict / Path=/ / 无 Domain；跨端口 Cookie 不能当本端口会话 | `OfflineEndToEndTest.consoleTicketExchangeSessionLeaseOperationResponse`（Set-Cookie 属性）/ `sessionCookiesAreIsolatedPerPort`（别的端口的 Cookie 名、改名后的值、控制台 ↔ 实例互换、同名注入、无 Cookie） |
| 3 | 精确 Origin；写请求 CSRF；敏感资源 `Sec-Fetch-Site: same-origin` + CORP | `OfflineEndToEndTest.consoleWritesRequireExactOriginAndCsrf` / `sensitiveInstanceResourcesRequireSameOriginAndHostIsExact`（含 Host 伪造 421、实例端口只读、路径穿越）/ `securityHeadersOnBothListeners`；`ContentControllerTest` |
| 4 | Adapter Protocol v1：envelope、namespace、错误码、能力四态；未知 operation 拒绝；adapter 拒绝未认证 loopback | `EnvelopeTest`、`EnvelopeSchemaContractTest`（schema ↔ Java 字段 / 枚举 / 错误码一致，fixture 正反例）、`AdapterDescriptorTest`（四态闸门、命名空间、`mmab.*` 保留）、`WireAuthTest`（含跨语言固定向量）、`ProcessAdapterClientTest.unauthenticatedLoopbackConnectionIsRefused`、`OfflineEndToEndTest.unknownOperationsDisabledCapabilitiesAndBadPayloadsAreRejected`；Node：`adapters/mock/tests/mock-adapter.test.mjs`、`sdk/driver-node/wire.test.mjs` |
| 5 | 一实例一租约，竞争只有一个胜出；旧 generation 的消息 / 操作被拒 | `LeaseManagerTest`（64 线程竞争、fence 单调、过期、换代驱逐）；`ProcessAdapterClientTest.mutualHandshakeThenFenceEnforcedByTheAdapter`（适配器自己拒绝旧 generation / 旧租约 / 无租约）；`OfflineEndToEndTest.leaseIsExclusiveAcrossConsolesAndTabs`（第二控制台 409、第二标签 WS 1013 且第一个不受影响）/ `generationBumpFencesOldSessionsTicketsAndFrames` / `consoleLogoutCascadesToInstanceSessionAndTransport` / `leaseExpiryRevokesSessionAndTransport` |
| 6 | codec 往返；队列满时的背压 / 合并 / 断开按设计；慢客户端不拖垮其他会话 | `StructuredCodecContractTest`、`sdk/web/codec.test.mjs`（同一份 fixture）；`PortTableTest`；`BoundedFrameQueueTest`；`SessionOutboxTest`；`OfflineEndToEndTest.slowClientIsDisconnectedWithoutHurtingOtherSessions`（32 MiB 推送给不读数据的客户端 → 该连接 `QUEUE_LIMIT` 断开，另一实例 5 次往返均 < 2 秒，断开后原会话可重连）/ `deadlineIsReportedAsUnknownNotFaked` |
| 7 | mock adapter + fixture，端到端：控制台签发票据 → 实例端口 exchange → 建会话 → 经租约发 operation → 收到响应 | `OfflineEndToEndTest.consoleTicketExchangeSessionLeaseOperationResponse`（登录 → 票据 → 303 + Cookie → fixture 首页含注入的 shim → 引导数据 → WS → `mock.echo` 往返 BigInt / Uint8Array / 中文 → `mock.add`）；审计里不含票据 / Cookie / 口令 |
| 8 | schema、adapter.yaml、文档同步 | 构建脚本用 JSON Schema（draft 2020-12）校验 `adapters/*/adapter.yaml` 与 envelope fixture；`AdapterDescriptorTest` 读取全部三个 adapter.yaml |

未覆盖 / 偏差见 `docs/adr/0004-m1-offline-skeleton.md` 第 3 节。
