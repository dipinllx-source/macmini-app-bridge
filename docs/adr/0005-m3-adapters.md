# ADR-0005：M3 参考适配器与证据闸门

- 状态：进行中（2026-11-09）——平台缺口与千问适配器已完成离线验收；Codex 适配器未开始。

## 1. 平台缺口（来自 ADR-0004 §3）

- 监听同时绑定 `mmab.console.bind-addresses` 中的每个精确地址（如 LAN IP + 127.0.0.1）；拒绝通配、主机名与重复；`public-hosts` 必须与之同步；IP 字面量 Host 必须等于连接实际到达的地址。
- `POST /__mmab/bridge/call`：只服务 `sync: true` 的操作（启动引导、`ipcRenderer.sendSync` 类），精确 Origin、同源、只收 JSON、不带端口、每次重查租约、10 秒期限、按实例并发预算。
- MessagePort 经 `PortTable` 在 WS 上中继（w/n 两个 id 空间、id 不复用、嵌套深度 / 数量预算、空闲回收）。
- `adapter.yaml` 新增 `web.inject`（按顺序注入经典脚本，先于原版脚本执行）与操作 `sync` 标记。
- 原版资源可位于 `mmab.data-dir`（`data:` 前缀），永不入库；原版页面自带 meta CSP 时只在 `connect-src` 追加本实例精确的 WS 来源。

## 2. 证据闸门

`adapter.yaml` 可声明 `evidence`：每项能力在**平台路径**上的最强证据（`offline` / `isolated-live` / `live`）。
声明了 `evidence` 的适配器，其 `SUPPORTED` 能力只有在证据达到 `isolated-live` 及以上，或操作者在实例上显式
`verify-capabilities` 授权时才开放。原型上的验收结论**不等于**平台路径已验收。未声明 `evidence` 保持 M1 语义。

## 3. 千问适配器（S1）的决定

- **注入与 driver 分离**：driver 不持有打开 inspector 的能力；注入是操作者显式运行的 `tools/inject.mjs`，写 0600 交接文件。driver 每次连接前重新核验 PID、启动时间、可执行文件与端口归属。
- **只注入精确 `--inspect=127.0.0.1:9229` 启动的进程**；inspector 关闭时 SIGUSR1 重开需要与本机构建 hash 匹配的信号证明。注入后无论成败都关闭 inspector 并复核。
- **主进程 ↔ driver 链路** `mmab-qwen-link-v1`：127.0.0.1 临时端口，每次注入生成 32 字节 key，双向 HMAC 握手，只保留一个已认证连接；值一律 mmab-sv1 文本，driver 不重新编码。
- **与旧原型桥共存**：独立全局名、独立 send hook 记录、tRPC id 从 3e9 起（原型 2e9），停止时只撤销自己那一层。
- **能力粒度**：所有 IPC 只能粗粒度归入 `ui.render`（tRPC 内的发消息等业务在 IPC 层不可区分），因此开放 `ui.render` 即开放原生账号的写能力，不是只读沙箱。
- **附件**（`webUtils.getPathForFile`）暂不支持：平台尚无受控的上传目录与能力闸门。

## 4. 已知偏差 / 后续

- driver 只认默认 `mmab.data-dir`（适配器环境变量 `MMAB_*` 被平台清除，引导行尚不带数据目录）。
- 千问实机验收（`isolated-live` 不适用：只有一个应用实例）需操作者批准后在正式实例上进行，结果写入 `docs/m3-acceptance.md`。
- Codex 适配器（S2：renderer CDP、app-host MessagePort、启动白名单、宿主请求过滤、≥700 帧突发）尚未实现。
