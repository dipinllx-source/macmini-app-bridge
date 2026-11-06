# tests

M1 的测试分布在各模块内（`mvn verify` 与 `node --test` 都会执行），对照表见 `docs/m1-acceptance.md`：

- Java 协议契约：`server/mmab-adapter-protocol/src/test`（envelope ↔ schema、codec fixture、线上认证向量、MessagePort 表）
- Java 安全 / 租约 / 队列单测与端到端：`server/mmab-server/src/test`（`e2e/OfflineEndToEndTest` 起真实 TLS 多端口 + 两个 Node mock 适配器进程）
- Node：`sdk/**/*.test.mjs`、`adapters/**/tests/*.test.mjs`

规划：lifecycle（恢复、阻塞、停止抑制，M2+）、browser-acceptance（真实 Chrome 分层验收，M4 前）。
