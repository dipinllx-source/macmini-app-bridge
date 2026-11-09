# ADR-0005：M3 参考适配器与证据闸门

- 状态：进行中（2026-11-09）——平台缺口完成；千问经平台在正式实例完成界面 / 历史验收；Codex 经平台在专用实例完成界面验收（isolated-live）；两者发消息均未验收。

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
- 千问实机验收已于 2026-11-09 经操作者批准完成（界面 / 历史，见 `docs/m3-acceptance.md`）；发消息需另行授权。

## 5. 实机暴露的平台问题

- 控制台端口的 Referrer-Policy 改为 `strict-origin`：`same-origin` 会让浏览器在跨端口开窗表单里发送 `Origin: null`，
  与“exchange 只认控制台 Origin”的规则冲突。实例端口保持 `same-origin`。这推翻了设计稿中“统一 same-origin”的写法。
- 出站缓冲预算可配置（`mmab.transport.*`），默认 16384 帧 / 32 MiB：设计 §9.3 的 2048 帧 / 4 MiB 起始值对真实应用
  开始任务时的推送突发过小（实机 1006 断线）；溢出原因写入审计。适配器 SDK 对超线路上限的单帧做隔离处理。
- 原版资源单文件上限 96 MiB（32 MiB 时千问 vendor 包 404 白屏），非 HTML 资源带弱 ETag / Last-Modified。
- Codex 适配器已实现（见 §6）；历史 / 发消息 / Git 未验收。

## 6. Codex 适配器（S2）的决定

- **只附加专用实例**：driver 每次附加前核验精确可执行文件、唯一且精确的 `--user-data-dir`、调试端口只绑 127.0.0.1；
  `ps` 输出不加引号，profile 位于 “Application Support” 下，解析按下一个 ` --` 截断。
- **页面进程侧**只经 CDP binding 与 driver 通信，不监听网络端口；每个浏览器连接一个端口中继（`n` id 空间）。
- **浏览器帧按序分批执行**（每批至多 256 条，排队上限 4096），满足启动时数百个端口帧的突发且保持端口内顺序。
- **宿主消息过滤**沿用原型：`*-request`、`codex_desktop:*`、分片消息由本机视图应答 / ACK，不镜像；带端口的消息不镜像。
- **Git** 用独立能力 `app.git` 闸门，未验收前关闭；浏览器侧 `connectGitWorker` 被拒时返回 true 并记录，不阻断渲染。
- **资源版本**：Codex 会自动升级，driver 比对提取资源的版本与已安装版本，不一致即 `BLOCKED_UPGRADE`。
- **账号**：实测全新 profile 启动即为已登录，独立 profile 不能作为账号隔离手段。
