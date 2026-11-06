# adapters/mock

M1 离线测试用的 mock 适配器（`strategy: test-mock`）。**不连接任何真实应用**，不读取任何账号或本机文件。
平台只有在 `mmab.testing.allow-test-adapters=true` 时才会启动它（默认配置为 false，启动即 BLOCKED）。

```text
adapter.yaml      能力四态与操作白名单（ui.compose 故意 DISABLED，用来验证网关拒绝）
driver/main.mjs   Node 适配器进程（基于 sdk/driver-node）
fixture/          固定 fixture "原版页面"：只调用平台注入的 window.mmab
tests/            适配器进程契约测试（node --test）
```

操作：`mock.echo`（原样返回）、`mock.add`、`mock.slow`（迟到响应）、`mock.burst`（推送 `mock.chunk` 与可合并的
`mock.tick`，用于慢客户端测试）、`mock.compose`（声明为 DISABLED，网关必须拦截）。

为什么用 Node 而不是 Java：见 `docs/adr/0004-m1-offline-skeleton.md` 第 1 节。
