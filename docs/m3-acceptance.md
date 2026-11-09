# M3 验收对照：参考适配器接入

证据层级：**离线**（自动化测试，假的应用进程）/ **独立实机**（独立应用实例，不影响操作者的正式实例）/
**实机**（操作者正式实例，需批准）/ **未验证**。

## 平台缺口

| 项 | 证据 | 测试 |
|---|---|---|
| 多地址精确绑定（LAN IP + 127.0.0.1），Host 必须等于到达地址 | 离线 | `MmabPropertiesM3Test`、`M3PlatformEndToEndTest`（127.0.0.1 + ::1） |
| 同步调用端点（仅 sync 操作、同源、租约重查、期限、并发预算） | 离线 | `M3PlatformEndToEndTest` |
| MessagePort 经 WS 中继（双向、嵌套、伪造 id 拒绝、关闭） | 离线 | `M3PlatformEndToEndTest`、`sdk/web/web-m3.test.mjs`、`sdk/driver-node/runtime-m3.test.mjs` |
| `web.inject` 注入顺序与 meta CSP 只追加本实例 WS 来源 | 离线 | `ContentInjectionM3Test` |
| 证据闸门 / 操作者授权 | 离线 | `AdapterDescriptorM3Test`、`QwenworkDescriptorTest` |

## 千问（S1）

| 项 | 证据 | 测试 / 说明 |
|---|---|---|
| 引导（argv 含主窗口 additionalArguments） | 离线 | `adapters/qwenwork/tests/qwenwork.test.mjs` |
| invoke 以主窗口身份执行；未注册通道 / 应用抛错以原文返回 | 离线 | 同上 |
| 拒绝作用于本机窗口 / 托盘 / 访达的通道（invoke / send / sendSync） | 离线 | 同上 |
| tRPC id 按连接重映射（3e9 段）、回复回到原连接、断开时停止订阅 | 离线 | 同上 |
| 主进程推送镜像给全部连接，原 send 仍被调用；tRPC 不镜像 | 离线 | 同上 |
| link 双向 HMAC；错误 key / 未认证帧被断开 | 离线 | 同上 |
| 交接文件缺失 → `ADAPTER_UNAVAILABLE`；权限非 0600 → 拒绝 | 离线 | 同上 |
| 注入表达式自包含（只用 Node 内置 + electron），在沙箱中对假应用执行成功 | 离线 | 同上 |
| 浏览器 shim：Electron 表面、本地剪贴板 / 外链（仅 http/https）、主窗口 hash | 离线 | 同上 |
| 从本机 App 包提取 renderer（464 个文件）+ preload 包装语法正确 | 独立实机（只读 App 包，写临时目录） | `tools/extract.mjs` 手工运行 |
| 注入预检：正式实例参数精确匹配、信号证明与本机构建一致 | 实机（`--dry-run`，未发信号、未连接） | `tools/inject.mjs --dry-run` |
| 真实注入（经操作者批准）：SIGUSR1 按信号证明重开 inspector → 注入 → inspector 关闭复核；应用 PID 与启动时间不变；旧原型桥并存且仍可用 | 实机（2026-11-09，千问 2.0.0） | `tools/inject.mjs --reopen-inspector --signal-proof` |
| 链路握手与引导、未知通道原文返回 | 实机 | driver link-client 直连 payload |
| 控制台登录 → 票据 → 跨端口表单 exchange → 实例会话（真实 Chrome） | 实机（headless Chrome，证书按 SPKI 精确信任） | 见下方“实机发现” |
| 原版界面经平台渲染：标题、`desktopApi` / `electronTRPC`、47 个按钮、输入框、无断线提示，0 未处理异常、0 console.error | 实机 | 同上；截图仅本地留存 |
| 打开历史会话，消息列表加载（5 条） | 实机 | 同上 |
| 能力闸门：`ui.render` / `ui.history` 证据升为 live 后无需操作者授权即开放 | 离线 + 实机 | `QwenworkDescriptorTest`；平台重启后复测同上 |
| 发消息 / 流式回复（ui.compose、stream.push） | **未验证** | 会在账号中留下记录，需操作者另行授权 |
| 语音（ASR 的 wasm 资源 401：疑为 worker 请求不带实例会话 Cookie） | **未验证 / 已知缺口** | `media.voice` 未开放 |
| 另一台实体内网电脑访问 | **未验证** | |

### 实机发现（已修复并补测试）

1. **跨端口开窗被拒（403）**：控制台响应 `Referrer-Policy: same-origin`，按 Fetch 规范跨源请求的 Origin 会被序列化为 `null`，exchange 只认控制台 Origin。M1 测试用 JDK 客户端手填 Origin，未覆盖。修复：控制台端口改 `strict-origin`（只发源、不带路径），实例端口仍 `same-origin`；`MacminiAppBridgeApplicationTest`、`OfflineEndToEndTest` 断言更新。
2. **白屏（vendor 包 404）**：千问 2.0.0 的 vendor 包约 37 MiB，超过 32 MiB 单文件上限。修复：上限 96 MiB；非 HTML 资源加弱 ETag + Last-Modified（`no-cache` 下可 304）；`ContentControllerTest` 新增 40 MiB 用例。

## Codex（S2）

未开始。
