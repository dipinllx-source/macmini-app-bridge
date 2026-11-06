# sdk/web

浏览器 / 应用内 JS 公共件（零依赖 ES module）。

| 文件 | 状态 | 说明 |
|---|---|---|
| `codec.mjs` | M1 已实现 | mmab-sv1 结构化值 codec（浏览器与 Node 共用），规范见 `../protocol/codec-sv1.md` |
| `shim.mjs` | M1 已实现（最小） | 平台注入到实例原版页面的桥：`window.mmab.{ready, invoke, on, close}`；断线可见提示；每 15 秒心跳续租 |
| `codec.test.mjs` | M1 | `node --test` 契约测试（共用 `../protocol/fixtures/codec-sv1.json`） |

平台把 `<script type="module" src="/__mmab/shim/shim.mjs">` 注入实例首页 `<head>` 之后，并只从本目录提供
`.mjs`（测试文件除外）。shim 拿不到票据、Cookie、租约 ID 或 fence。

规划（M3 起，来源于两个原型桥的已验证经验）：非安全上下文补齐（`crypto.randomUUID`、SHA-256 digest、
`clipboard.writeText`、`mediaDevices` 占位）、preload 同世界兼容、嵌套 MessagePort 中继、启动超时提示。

注意：`shim.mjs` 与 fixture 页面尚未在真实浏览器中执行验证（M1 的端到端测试用 JDK 客户端模拟浏览器）。
