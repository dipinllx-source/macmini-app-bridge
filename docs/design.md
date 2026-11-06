# MiniMacAppBridge（miniMac App 引桥）：统一桌面应用桥接平台架构设计

> 平台名称见第 15 节；扩展能力见第 14 节。千问、Codex 是首批两个接入应用，不是平台边界。
>
> 公开版说明：原型代码与验收证据不在本仓库，文中涉及原型的源码位置已删除，仅保留机制结论。

- 文档状态：设计评审稿；2026-11-06，Asia/Shanghai。
- 实施状态（2026-11-07）：**M1 离线协议与安全骨架已完成**，仅 mock 适配器 + fixture 页面，未接入任何真实应用；验收对照见 `docs/m1-acceptance.md`，与本文的偏差见 `docs/adr/0004-m1-offline-skeleton.md`。M2 及以后未开始。
- 默认场景：单 Mac、可信 LAN、单操作者；一个 Spring 服务，两个本地应用适配器。多节点是后续扩展，不是 MVP 前提。
- 本轮边界：只读项目、既有验收材料和官方文档；只产出本文。不实现代码、不安装依赖、不修改安装包/签名/fuses/LaunchAgent、不启动、停止、重载或探测运行中桥的业务接口。
- 证据时态：下文“已验证”指仓库留存的指定版本验收，不代表本轮重新验收，更不代表当前服务在线。源码路径均相对 workspace；行号对应本轮读取快照。

## 1. 结论与决策摘要

**统一的是入口、授权、编排、生命周期、代理和验收标准，不是把两个 Electron 桥强行写成同一套 IPC 实现。**

1. 使用 Java 21 LTS + 当前受支持的 Spring Boot 4.1.x GA 线建设模块化单体；Vue 3 + TypeScript + Vite + Pinia + Vue Router + Element Plus 只负责管理控制台。启动时仍展示原版应用 HTML/CSS/JS，不用 Vue 重写千问/Codex UI。
2. 第一阶段封装现有 Node 适配器；保留千问主进程 JS 和 Codex renderer JS。Java 可以逐步接管 HTTPS、会话、进程编排和代理，但不能让 Electron 的 JavaScript 对象、ipcMain 或 MessagePort 在 JVM 内凭空出现。
3. **同一 IP、每个应用实例独立端口**（2026-11-07 操作者决定）：控制台 `:8443`，千问 `:8444`，Codex `:8445`（建议值），一个 Java 进程多监听；不依赖 DNS 或 hosts，原版应用仍在根路径运行，无需路径兼容层。端口隔离存储与脚本，但不隔离 Cookie，需按第 5 节加固。V1 新标签打开；当前 Codex 显式禁止 framing，不能宣称已能嵌入。
4. Java 是唯一外部认证入口和数据面代理；本地 adapter 也必须校验独立服务身份、实例、generation、租约和最小操作授权。不能因为请求来自 loopback 就免认证，也不能把原始 CDP/inspector 作为公共 API。
5. 单机 SQLite；多节点/多个控制平面写者才引入 PostgreSQL。不引入 Redis、Kafka、Kubernetes、服务注册中心或微服务拆分。
6. 首版每实例一条 exclusive lease，覆盖本机浏览器/LAN 浏览器所有 tab；不承诺多账号隔离、多人协作、只读安全沙箱。Codex app-host 有宿主账号写权限；独立 user-data-dir 不等于独立账号。
7. 用 desired-state / actual-state / reconcile 表达恢复；版本不兼容、普通千问进程无法安全注入、端口归属不明时 blocked，不以无限重启掩盖问题。迁移必须消除现有千问 launchd job 与平台的双拉起。

### 1.1 非目标

不提供截图流远程桌面；不实现新的 AI 聊天前端；不迁移/读取原应用账号凭证；不绕过原生授权；不把本机窗口与远端操作变成隔离会话；不保证所有 IPC、worker、附件、语音、MCP 或 Git 功能可用。应用自身访问外部服务的行为也不因桥接而自动收敛到 Java。

## 2. 现状：两个原型桥

本平台源自两个已在单机上验证过的原型桥。原型代码不在本仓库中，下文只概括机制和已验收范围。

### 2.1 千问：主进程注入（策略族 S1）

链路：浏览器加载原版 renderer → shim 模拟 Electron 环境 → 原版 preload → HTTP/WS → 注入应用主进程的桥 → ipcMain / 主窗口 webContents。

- 应用允许以 inspect 参数启动，桥通过调试协议在主进程内加载，注入后立即关闭调试端口。
- 桥在主进程内调用 IPC 处理器，并以真实主窗口身份发起调用，因此**与本机窗口共享同一账号与状态**，不是隔离会话。
- 依赖应用私有的 IPC 实现细节，应用升级后必须回归。
- 原型使用 HTTP、本机免口令，安全性只达到 PoC 水平，平台化时须统一收敛。

### 2.2 Codex：renderer CDP（策略族 S2）

链路：浏览器加载原版 webview → shim / 端口 codec → HTTPS/WS → renderer CDP → 原生 preload 暴露的桥对象 / window.postMessage / MessageChannel。

- 应用禁止主进程调试，只能经 Chromium 远程调试端口进入 renderer；该端口**需常开**，本机其他进程理论上可借此控制应用（仅监听 127.0.0.1）。
- 原版页面依赖 MessagePort RPC，单纯转发普通消息不足以渲染，必须中继嵌套端口。
- 登录后启动会瞬时产生数百条端口消息，限流过严会断线；部分启动请求必须放行，否则界面为空壳。
- 宿主的心跳/快照类请求由本机窗口应答，网页端需过滤，避免重复应答。

### 2.3 能力矩阵（以原型验收证据为准，不是平台承诺）

| 能力 | 千问 | Codex |
|---|---|---|
| 原版界面渲染 | 已验收（含升级后版本） | 已验收（登录后输入框可见） |
| 历史会话浏览 | 已验收 | 未验收 |
| 新建任务 / 流式结果 | 旧版本已验收，新版本未复验 | 未验收 |
| 平台级鉴权 | 原型级（HTTP、本机免口令） | HTTPS + 口令 + Origin 校验已测 |
| 多标签 | 原型允许多客户端 | 第二标签提示占用，首标签不受影响 |
| Git / 附件 / 语音 / 剪贴板 | 多项未验证；HTTP 下语音不可用 | Git 仅握手；其余未验证 |
| 断线重连 / 升级自动恢复 | 部分；普通方式启动的新版本需人工 | 需人工 |
| 账号隔离 / 只读沙箱 | 不成立 | 不成立 |
| 其他实体电脑访问 / iframe 嵌入 | 未验收 | 未验收；当前禁止 framing |

端到端验收必须分别记录 SETUP、AUTH、TRANSPORT、RENDER、BUSINESS 五层，不能用监听存在、接口 200 或页面有根节点代替业务可用。

## 3. 技术选型

| 层 | 明确推荐 | 理由与锁版要求 |
|---|---|---|
| JDK | Eclipse Temurin JDK 21 LTS，按发行方补丁维护 | 21 是长期支持线；适配生态稳定。锁定 21.0.x 补丁与 macOS arm64 包 hash，补丁号实施前查官方；不继承本机旧 JAVA_HOME 假设 |
| 服务框架 | Spring Boot 4.1.x 当前 GA 受支持线 + BOM 管理依赖 | 官方当前系统要求页面返回 4.1.2，Java 17–27，包含 JDK21；4.1.2 仅为本次页面观测，不冒充已锁定交付版本。正式锁版前核对支持矩阵/EOL及 GA 制品，不用 SNAPSHOT/RC，不因熟悉就选可能已过 OSS 支持窗口的旧 3.x |
| HTTP/WS | Spring WebFlux + Reactor Netty，一个服务承担 REST、静态入口和双向 WS 代理 | 同一运行时支持 HTTP client/server 和 WS 背压；阻塞的进程查询、hash、SQLite 操作进有界专用 executor，不运行在 event loop。无需混用 MVC 和 WebFlux 两套 server |
| 安全 | Spring Security WebFlux + 服务端随机 session + 显式 CSRF/Origin 策略 | 不用长效浏览器 JWT，不把 access token 放 localStorage；会话、租约可撤销 |
| 数据访问 | Spring JDBC / 明确 SQL + SQLite JDBC 驱动；迁移脚本版本化 | 单机事务边界直接可控，避免为 SQLite 引入不成熟 ORM 方言。阻塞调用移出事件循环；驱动 arm64 与 Boot4/Jackson 兼容需离线契约测试 |
| 管理前端 | Vue 3、TS strict、Vite、Pinia、Vue Router、Element Plus | 页面以实例列表、状态、兼容矩阵、审计、开窗、确认操作为主；Pinia 只存视图/状态，不存票据或密码。UI库只作用于管理 origin |
| adapters | 当前 Node + ws，分离 qwen/codex profile | 优先包装既有正确行为；Node 运行时锁支持中的 LTS，具体补丁/依赖 lockfile 在实施时锁定；不再依赖另一个项目 node_modules 的绝对路径 |
| 持久化 | SQLite 单机本地磁盘；PostgreSQL 多节点阶段 | SQLite WAL、foreign_keys、busy_timeout、短事务、串行写预算；不放 NFS，不用于高频帧存储。多控制节点/跨节点事务时迁 PG，schema 避免 SQLite 特有业务语义 |
| 打包与运维 | 单个 Spring distribution + Vue 构建产物 + 两 adapter 制品 + profile manifest | 首版不做 GraalVM/native image，不容器化 GUI App。后续用户级 launchd 只保活平台，应用恢复由平台唯一 reconcile owner 负责 |

### 3.1 官方核对及限制

本轮读取：

- https://docs.spring.io/spring-boot/system-requirements.html ：返回 Spring Boot 4.1.2 / Java 17–27 / Spring Framework 7.0.10+，嵌入式 Tomcat11 或 Jetty12.1（选择 WebFlux 时使用其支持的 Reactor Netty，不把 Servlet 容器要求套成必须安装 Tomcat）。
- https://docs.spring.io/spring-boot/4.0/system-requirements.html ：返回 4.0.9 同样兼容 JDK21；作为备选证据，不据此自动判定其余支持期。
- https://github.com/spring-projects/spring-boot/wiki/Supported-Versions ：维护政策为 major 至少三年但必须运行受支持 minor；minor 至少十二个月。抓取正文有“不完整”提示；具体支持矩阵链接 https://spring.io/projects/spring-boot#support 未被正文提取器完整读出。
- https://www.oracle.com/java/technologies/java-se-support-roadmap.html ：21 为 LTS；Oracle 商业支持期限不能当作 Temurin 的承诺，也不代表 Oracle JDK 任意商业用途免费。
- https://vite.dev/guide/ ：确认 Vite 构建/开发工具角色；生产不运行 Vite dev server。

**明确推荐 JDK21 + Boot4.1 GA 线；精确补丁、该线支持结束时间、Temurin 支持承诺和三方驱动兼容在实施锁版门禁中核定。** 不能把 Java 版本兼容等同于商业/OSS 生命周期支持。若届时 4.1 不再受支持，切到官方仍支持且兼容 JDK21 的 GA 线，更新 ADR，不静默回落旧版。这里没有安装或编译验证。

## 4. 总体架构与平面边界

```text
                   Trusted LAN browser (single operator)
                   /                                \
    https://<LAN-IP>:8443/                    https://<LAN-IP>:8444/  (qwen-01)
    Vue management console                    https://<LAN-IP>:8445/  (codex-01)
    instances / health / audit                 Original app UI + minimal JS shim
                   | REST / status events            | HTTPS assets / WSS
  +----------------v---------------------------------v-------------------+
  | ONE Java Spring service (TLS, exact virtual-host routing)             |
  |                                                                      |
  | CONTROL PLANE                      DATA PLANE                        |
  | identity / sessions / launch ticket  per-instance cookie enforcement |
  | instances / profiles / compatibility HTTP + WS streaming proxy       |
  | desired/actual state / reconcile     lease + generation fencing       |
  | exclusive leases / audit            bounded buffers / deadlines       |
  | local SQLite metadata               no message-body persistence       |
  +--------------------------+------------------------+-------------------+
                             | authenticated local adapter protocol
                    +--------+---------+----------------+
                    |                  |                |
       +------------v----------+ +-----v----------------v---------+
       | Qwen Node adapter     | | Codex Node adapter             |
       | process/inject driver | | dedicated process/CDP driver   |
       | auth local endpoint   | | auth local HTTP/WS endpoint    |
       +------------+----------+ +------------------+-------------+
                    | signed/verified lifecycle      | loopback-only CDP
                    v                                v
       +-----------------------+ +--------------------------------+
       | Qwen Electron MAIN    | | Codex Electron RENDERER        |
       | injected bridge JS    | | injected JS / electronBridge   |
       | ipcMain / webContents | | MessageChannel / app-host      |
       +-----------------------+ +--------------------------------+
```

图中端口为建议值，尚未部署。Java 一个进程开多个 HTTPS 监听，每个监听对应控制台或一个应用实例；不需为每个应用部署微服务。

- 控制平面不传聊天正文，不尝试统一各 App 的业务 API；负责“谁、可使用哪个实例、哪一代、由谁恢复、哪些能力已验收”。
- 数据平面代理原版资源和桥消息；做固定 envelope 校验/授权、限额、时限、撤销、关闭，不解析并记录所有业务正文。
- adapter 处理原生协议与版本差异。Java 不公开 eval、exec、任意文件路径、任意 CDP 方法或任意 IPC channel。
- 对 app-host 的授权只能诚实标为 NATIVE_APP_CONTROL 粗粒度能力。未知 RPC 透传后，不能宣称已实现逐业务只读权限；若要求细粒度限制，必须先理解/约束该版业务协议，否则禁用该能力。
- 不需分布式消息总线。状态事件用控制台 SSE，原版 UI 数据用 WS；不同语义不混在一个无限制通道里。

## 5. 访问入口：同一 IP、按端口区分应用

> 2026-11-07 操作者决定：采用**同一 IP、每个应用实例独立端口**。不依赖内网 DNS 或各终端 hosts；评估过的“同端口按路径区分”方案因 Codex 绝对资源路径、自带 Service Worker、前端路由等兼容风险及同源不隔离而放弃（评估稿备份：`.openclaw/tmp/unified-design.path-variant.md`）。

### 5.1 端口布局

```text
https://<LAN-IP>:8443/    Vue 控制台 + 平台 API（/__mmab/...）
https://<LAN-IP>:8444/    qwen-01   千问原版界面（根路径）
https://<LAN-IP>:8445/    codex-01  Codex 原版界面（根路径）
https://<LAN-IP>:8446-8459/  预留给后续接入的应用实例
```

- 端口号为建议值，部署时在配置中确定；实例与端口**静态绑定**，地址稳定、可收藏。
- **一个 Java 进程开多个 HTTPS 监听**（Reactor Netty 多端口），不为每个应用部署独立服务；每个监听只服务一个已登记实例，不做开放代理。
- 监听只绑定精确 LAN IP 与 127.0.0.1，不绑 0.0.0.0；端口被其他进程占用时实例进入 `BLOCKED_PORT_OWNER`，不抢占、不自动换端口。
- 一张证书（SAN 含该 LAN IP 和 127.0.0.1）覆盖所有端口；访问端每台电脑只需信任一次。
- 原版应用照旧运行在根路径，**不需要路径兼容层**。现有千问（8686）、Codex（8787）两个桥都是“独立端口 + 根路径”，已有渲染验收证据，迁移风险最小。

### 5.2 端口隔离能做到什么、做不到什么

浏览器的“来源”包含端口，因此：

| 项目 | 是否按端口隔离 | 说明 |
|---|---|---|
| 页面 DOM、脚本互访 | 是 | 不同端口是不同 origin |
| localStorage / IndexedDB / Service Worker / 缓存 | 是 | 各应用互不覆盖，Codex 自带 SW 不受影响 |
| WS / 请求的 Origin 头 | 是 | Origin 含端口，可精确校验 |
| **Cookie** | **否** | 同一 IP 的 Cookie 会发给所有端口 |
| **SameSite** | **否** | “同站”判断忽略端口，跨端口请求属于同站，SameSite=Strict 挡不住 |

因此 Cookie 与跨端口请求要按以下规则加固（第 6 节的安全要求同样适用）：

1. **每个端口用不同名字的会话 Cookie**：控制台 `__Host-mmab-console`，实例 `__Host-mmab-i-<id>`；均 Secure、HttpOnly、`Path=/`。各监听只认自己的 Cookie 名，收到别的 Cookie 一律忽略。HttpOnly 保证其他端口的页面脚本读不到。
2. **所有写操作和 WS 升级必须精确校验 Origin（含端口）**，拒绝缺失或 `null` 的 Origin；不能依赖 SameSite 防跨端口伪造请求。
3. **敏感资源只允许同源加载**：桥接引导数据（如 Codex `bootstrap.js` 中的启动元数据）、平台 API 的 JSON 响应，要求 `Sec-Fetch-Site: same-origin`，并加 `Cross-Origin-Resource-Policy: same-origin`，防止其他端口页面用 `<script src>` 跨端口读取。现有 Codex 桥只拒绝 `cross-site`，迁移时要收紧为只允许 `same-origin`。
4. 实例会话只在对应端口签发和校验；某端口会话泄漏不能换取其他端口访问。

### 5.3 开窗：从控制台进入应用端口

控制台和应用端口是不同 origin，开窗使用一次性票据换取实例会话：

1. 操作者在控制台（8443）登录，点击“打开应用”。
2. 控制台同源 `POST /__mmab/api/v1/instances/{id}/launch-tickets`：校验 CSRF + 精确 Origin，原子获取该实例独占租约，生成单次票据（建议 30 秒有效，服务端只存 hash），绑定控制台会话、实例、目标端口、租约和 generation。
3. 控制台以表单 POST 把票据提交到目标端口的固定地址 `https://<LAN-IP>:<实例端口>/__mmab/session/exchange`，新标签打开。票据只在 POST 正文中，不进 URL、日志或浏览器存储，也不交给原版应用脚本。
4. exchange 是**唯一允许控制台跨端口 POST 的地址**：校验来源恰为控制台 Origin、票据未过期未使用且绑定一致，原子消费后签发该端口的实例 Cookie，303 跳转到 `/`。不开放通用 CORS，缺失或 `null` Origin 直接拒绝。
5. 之后该端口的资源与 WS 校验：实例 Cookie + 当前租约/generation + 精确 Origin。控制台注销、租约撤销或实例重建时，关闭 WS 并使实例会话失效。

Referrer-Policy 使用 `same-origin`，以保证同源表单带合法 Origin（Codex 设置页曾因 `no-referrer` 产生 `Origin: null` 被拒，见第 6.3 节）；跨端口提交的 Origin 由浏览器按 CORS 规则发送，不依赖 Referrer。

### 5.4 嵌入与后续

- V1 在新标签打开，不做 iframe：当前 Codex 显式禁止 framing，跨 origin 嵌入还涉及 Cookie、弹窗、剪贴板等问题，需单独 spike。
- 将来有内网 DNS 时，可把“端口”平移为“子域名”，Cookie 隔离会更彻底；网关、租约、适配器协议均不变。
- 新接入应用只需在预留端口段分配一个端口并登记，不影响已有实例的地址。

### 5.5 端口方案验收

1. 各实例端口只监听精确 LAN IP 与 127.0.0.1，监听进程为平台 Java 进程。
2. 从控制台开窗：票据单次有效、过期失效、换错端口失败、无票据直连仅见登录提示。
3. 跨端口防护：A 端口页面无法读取 B 端口引导数据，无法以 B 端口身份建立 WS 或调用写接口。
4. 两个应用同时打开，各自存储、Service Worker 互不影响。
5. 每个应用的原版界面渲染、启动突发消息、第二标签占用提示与现有桥一致。
6. 另一台实体内网电脑按同样步骤访问通过。

## 6. 认证、授权与本机信任边界

### 6.1 三种不同身份

- 人的身份：平台操作者，访问密码由 host-owned 的本机 TLS 页面初始化，仅存盐化强 hash。密码不是千问/OpenAI 密码，不经过聊天、命令行参数或日志。原生账号登录仍由操作者在精确的原生窗口完成。
- 浏览器会话：服务端随机 session，建议最长 8 小时、闲置 30 分钟，超时/注销关闭 WS；持久状态保存密码 hash，不持久化明文 session。管理和实例 session 分离；多 session 不代表多操作者隔离。
- 适配器身份：Java 与每个 adapter 的私有服务身份，不能拿浏览器 session cookie 作为服务身份。

### 6.2 local adapter 也要认证

V1 推荐每次启动 adapter 生成的随机服务密钥，经受控匿名 stdin/继承 FD 传递、进程内保存，不放 argv、URL、环境变量、磁盘日志。双方在固定管理/数据协议握手中证明身份，并绑定 hostId、instanceId、generation、adapterVersion；每请求带有认证及 deadline/nonce，拒绝重放。可用受保护 UDS 降低端口暴露，但 UDS 路径/同用户访问仍非完整身份；同用户恶意进程不在可被彻底隔离的威胁模型内。

现有 Qwen 的本地 HTTP 免口令不能用作最终 adapter 接口。它的 bridge 仍在 App 主进程，不是外部 Node sidecar：未来要最小改造其注入端点为仅 loopback、校验服务身份，并由 Qwen driver 调用；不要让 sidecar 仅转发到不设防的旧端口。Codex 同样需要内部 adapter 认证与外部浏览器会话解耦。此为实施前提，本文未做改造。

adapter SPI 只允许固定 profile 对应的 discover/probe/start/attach/stop/openTransport 操作。不得接收任意 shell 命令、JS 字符串、CDP method、目标 URL 或文件路径作为平台 API 参数。进程启动参数来自静态 profile 白名单。

### 6.3 Web 安全默认值

- TLS 默认必需，监听精确授权 LAN 接口；host-owned 初始化完成前不开放 LAN。部署证书通过自有/组织 CA 或人工验证指纹，不要求关闭浏览器校验，不自动全局信任证书。
- 管理写 API：CSRF token + 精确 Origin；WS 升级：cookie + 精确 Origin + Host + lease；Fetch Metadata 作为补充而非唯一防线。拒绝 null/missing Origin 的敏感请求。
- 使用 Referrer-Policy `same-origin`，跨源不泄漏 referrer，同源表单保留合法 Origin。不要机械套用 `no-referrer`：Codex 的真实 Chrome 表单曾因此发出 Origin:null；禁止通过放行 null Origin“修好”。票据本就不在 URL，故不依赖 referrer 隐藏票据。
- upload/resource proxy 单独授权、路径根限制、符号链接检查、文件类型/数量/大小预算；默认关闭尚未验收的附件/本地文件代理。只允许 profile 规定协议，不提供任意本机文件读取或 SSRF 出口。
- 认证限流按可信 socket 来源与全局预算，日志不记录 forwarded headers 中的伪造身份。不将当前 PoC 特定 /24 规则写成适用于所有企业 LAN 的通用发现算法。
- 审计只记操作者内部 ID、实例、动作、授权结果、错误码、时间、generation、profile/version、lease 摘要、耗时与计数；不记正文、文件内容、完整文件路径、账号标识、cookie、票据、口令、CDP 表达式、异常正文或带参数 URL。代理访问日志与异常处理也须脱敏。

Codex CDP 常开有不可消除的本机控制风险：其他本机进程仍可能接入其 loopback 端口；平台身份认证不能给 Chrome CDP 增加原本不存在的认证。MVP 接受单操作者受信 OS 用户边界，明确标风险；不接受则拒绝启用 Codex adapter，不能靠“loopback 安全”带过。千问按需开启 inspector、身份验证后注入、finally/watchdog 关闭，关闭失败标记 blocked 并人工处理，禁止偷偷杀 App。

## 7. 生命周期、恢复与版本兼容

### 7.1 desired-state 与 actual-state

建议 desiredState 为 RUNNING / STOPPED / OBSERVE_ONLY；恢复开关和 manualStopSuppression 独立持久化。actual-state 是观测，不以“start API 返回成功”覆盖实态。

```text
 DISCOVERED -> OBSERVING -> COMPATIBILITY_CHECK -> STARTING/ATTACHING
                  |                 |                    |
                  |                 v                    v
                  |               BLOCKED <---------- PROBING
                  |                                      |
                  +------ WAIT_CONFIRM <--- DEGRADED <--- READY
                                |             |
 explicit stop -> STOPPED_SUPPRESSED           +-> BACKOFF -> re-observe
```

- 每实例串行 reconcile，有 owner lock、deadline、指数退避+抖动、最大启动尝试预算。每次有副作用前重新核对 PID + 启动时间 + UID + 可执行文件 + profile + listener ownership，避免 PID 复用和 check/use 竞态。
- actual-state 至少包含 app/adapter PID 与 startTime、observed generation、listener owner/bind address、renderer target、manifest hash、健康层级、最后观测时间、blocked reason。
- STOPPED 是明确意图；写入 suppression 后才关闭受控桥，默认不退出用户正常 App。再次手动启动才解除 suppression，不因下一轮健康检查自动拉起。异常退出与主动停止不能只靠“进程没了”区分。
- WAIT_CONFIRM 用于需要退出确认、账号核对、首次 CDP 风险接受、升级新 profile 验证等。不可自动点击退出框、清空输入、结束正在执行的任务；已有 helper 可做某操作不等于平台默认有权执行。
- 端口冲突返回 BLOCKED_PORT_OWNER，不 kill 占用者、不盲换到未登记公共端口。健康超时不直接等于进程已死。

### 7.2 不同应用恢复策略

**千问**：保留  的保守决策：普通启动实例不发 SIGUSR1；仅精确 inspect 参数、受控进程和匹配框架 hash 的已证明版本允许重开。现有稳定窗口 120 秒、启动节流 180 秒和 60 秒至 15 分钟退避可作为初始 profile 参数，不当作更新器完成证明。现有健康仅 `/__qw/shim.js`，不能升格为 DOM/业务健康。

**Codex**：只 attach 专用 profile 且唯一 loopback CDP owner；不能接普通登录实例。CDP/renderer 上下文丢失时 fence generation、关闭 WS/端口与待决调用，再观测重建；不得把旧端口 ID 接到新的 renderer。普通窗口保持不动，专用实例退出确认需人工；detach 后 PPID1 不是恢复策略。

### 7.3 版本 manifest / adapter profile

每个受支持组合必须有一份不可变 manifest，包含：app bundleId/version/build、可执行/asar/framework 指纹、preload/入口资源 hash、Electron/Chromium 版本、adapter/profile/shim/codec 版本、期望桥表面/target、能力白名单、队列预算、安全注入证明、已验收层级、证据引用和 rollback 指向。

现有 Codex  只比较版本/asar size/mtime；千问 signal-proof 只证明特定 framework 重开 inspector 的行为。平台需要分清“文件稳定”“可安全重开”“协议兼容”“DOM 可用”“业务已验收”五种证明，不能一个 hash 包办。

升级流程：检测指纹变化 → 禁止新 lease、旧 generation 失效 → 原子登记候选 manifest → 提取到新的只读资源目录 → 离线测试 → 人工批准受控现场验证 → 原子切 active manifest。保留 active/previous 双版本 adapter + assets + manifest；不能混用旧 HTML 与新 preload。

回退只能恢复与**当前原生 App 版本**兼容的旧 adapter/assets。若 App 已升级而旧 adapter 不兼容，默认 BLOCKED_UPGRADE、只观察，不能声称切资源就回退了 App；原生包降级、账号数据库兼容或重装需独立授权，平台不改安装文件。

### 7.4 launchd 单一所有权

原型已有一个用户级 launchd 自启检查 job（每 30 秒）。未来迁移方案：

1. 保持原 job 作为唯一 owner，Java 先 OBSERVE_ONLY；不安装新 scheduler，不竞争注入锁。
2. 记录原 plist/job enable 状态、adapter/profile manifest、监听归属，不拷贝/展示账号或密码。
3. 经迁移授权，先 disable 旧 job，允许已运行检查结束，确认无 active injector；必要时 WAIT_CONFIRM。不能 bootout 一个正在注入的检查器。
4. 再授予平台 reconcile owner。未来平台 LaunchAgent 只保活 Java，不能另一 job 也直接拉 App；driver 不自带独立重启循环。
5. 回滚时先把平台变为 OBSERVE_ONLY/停止恢复、撤销迁移中的 lease并确认无 in-flight 操作，再恢复旧 job 原状态。禁止双 owner 共存。

运行中的旧 Qwen 桥若仍绑定 `*:8686`，即使新入口已鉴权，绕过入口的旧路径仍在。完整安全迁移必须在批准的切换窗口将其收敛为认证的内部端点并关闭旧外部入口；在那之前只能标“观察/过渡”，不能标“平台统一鉴权完成”。这不要求本轮停止现有服务。

## 8. 健康模型：监听不等于能用

| 层级 | 检查 | 自动检查边界 |
|---|---|---|
| L0 PROCESS | PID/startTime/exec/UID/profile 和进程 generation | 只读 OS 状态；不能只 pgrep 名称 |
| L1 LISTENER | 所有权、精确 bind、TLS、私有 adapter 身份 | 未授权端口即 blocked，探活不绕鉴权 |
| L2 BRIDGE | preload/IPC 表面、renderer/context、绑定、manifest 匹配 | 无业务副作用的固定 probe，不公开任意 evaluate |
| L3 RENDER | 原版 DOM 特征、WS存活、启动完成、资源/异常统计 | 受控浏览器只读验证；setup DOM 与 app DOM 分开，业务文本不采集 |
| L4 BUSINESS | 经授权发送一个可辨识测试任务，确认结果/流式/取消 | 默认人工触发；不能定时发消息、读私密历史或跑 Git 当健康检查 |

每层记录 verifiedAt / appVersion / manifest / generation / probe 类型，不用单一绿色 healthy 掩盖 L4 未验证。控制台直接展示“桥在线 / 页面已验收 / 业务未验收”等事实；当前 renderer DOM 就绪也不意味着远端 browser DOM 就绪。

## 9. 数据通道、租约与类型契约

### 9.1 一实例一独占租约

原生 App 状态仍共享。Java 用数据库事务/唯一约束保证每实例一个 ACTIVE lease，绑定操作者 session、client/tab ID、generation、expiresAt、fencing token。建议 15 秒 heartbeat、60 秒到期；最终参数需稳定性测试。第二 tab 默认 409 / WS1013，不能清理第一 tab 的全局端口（现有 Codex 占用回归需保留）。转移控制需显式确认，不做 last-writer-wins。

控制面 lease 是所有进入实例数据面的前提，adapter 也检查 fence。租约过期、管理 logout、实例 session logout、App 重启或兼容 blocked：立即拒绝新帧、关闭 WS、取消本连接 pending RPC、停止订阅、回收端口，不自动重放带副作用的命令。取消桥请求不保证取消 App 已经开始的任务，UI 必须标“执行状态未知”，不能声称 exactly-once。

### 9.2 envelope 与消息 namespace

统一 envelope 字段：protocolVersion、profileId、instanceId、processGeneration、connectionId、leaseId、fencingToken、streamId/portId、sequence、correlationId、messageKind、deadline、codecVersion、payload。Java 校验 envelope 和预算；原生业务 payload 由 profile 专用 codec 解释，不统一业务名词。

generation 至少在 App/renderer context/adapter 注入重建时递增；connectionId 每次重连新建。重连后旧 generation/lease/port 消息一律拒绝，不能单靠当前 `native:1` / `web:1` 或千问整数偏移。重连仅恢复固定引导/订阅握手，不 replay 已发送任务。WS close/reload 清理对应连接而非所有客户端全局状态。

### 9.3 有界队列与 backpressure

Codex 现场曾因启动超过 600 帧触发旧 256fps/64 pending 限额而白屏；之后还必须补 `request-app-server-state` 才渲染。说明“放大队列”与“协议引导完整”是两项验收。

MVP 起始预算沿用已验收 Codex 边界：单帧 1 MiB、入队 2048 帧/8 MiB、4096fps 突发保护、出站 buffered 4 MiB 后慢客户端断开；这些是起始值，不是所有 App 的永久通用值。Java 和 adapter 都要有字节/帧/端口/待决 RPC/时间维度上限，端到端总预算不能因双层缓冲翻倍失控。

默认每实例顺序处理有状态端口消息；getter 可用小型受控并发，但不得破坏消息/端口因果次序。建立高低水位、可读暂停/credit、写失败/超时处理；浏览器 WS 无原生接收背压时由应用级 credit 或安全关闭兜底，不在 native 主线程无限等待。控制/心跳有保留额度，不能被大消息饿死。不丢弃任意业务帧；仅 profile 显式标为可合并的状态通知可合并。startup timeout 只提示，迟到成功可解除提示，不能默默判业务成功。

### 9.4 codec / MessagePort 生命周期

- 外层版本化 envelope + profile 专用 codec；V1 不强行把两者重写成同一 codec。Java 透明传输时亦有帧长度/嵌套层级限制；不把 JSON payload 反序列化成任意 Java 类。
- 千问已有 undefined/BigInt/nonfinite/Buffer/ArrayBuffer/Date/Map/Set/Error 编码，但循环被转 null、typed view 可能退化，不能说是完整 structured clone。
- Codex 现有 JSON-like/undefined/ArrayBuffer/basic typed arrays/nested MessagePort；Map/Set/Date/Error/Blob/stream/cycle 不完整。对未支持类型显式 CODEC_UNSUPPORTED，不静默转空对象或损坏精度。
- port open/transfer/data/close/error 明确定义；每连接 port 上限建议 256、深度建议 32、闲置 5 分钟回收，具体随 profile 通过实测。转移所有权、重复 transfer、关闭后帧、丢失 remote peer、半关闭均有契约测试。
- 数据/端口控制字段分离并防类型标记碰撞；严格 schema，禁止 __proto__/constructor 原型污染。二进制版本升级可以后续引入，不让 V1 在 JSON/base64 上无限承载媒体。

## 10. 核心实体、API 与 adapter SPI

### 10.1 核心实体

| 实体 | 关键字段 / 不变量 |
|---|---|
| Host | id、平台/架构、GUI用户、可信网络策略；MVP 只有本机记录，不提供远程任意执行 |
| AppDefinition | QWEN/CODEX、bundleId、固定允许路径、风险说明 |
| AdapterProfile | version、适用 app fingerprint、能力、安全策略、codec、probe、证据状态 |
| BridgeInstance | id、host/app/profile、origin、desired/actualState、generation、owner、suppression、lastObservedAt |
| RuntimeObservation | PID/startTime、端口所有者、target/context、健康层、blocked code；TTL，非永久可信凭据 |
| OperatorSession / InstanceSession | hash、作用域、expiry、parentSession、lease/generation；不存明文 token |
| ExclusiveLease | instance 唯一 active、session/client、fence、heartbeat/expiry；事务 CAS |
| LaunchTicket | hash、targetOrigin、parentSession、instance/lease/generation、短 TTL、consumedAt |
| Operation | id、idempotencyKey、动作、确认要求、状态、generation、deadline、结构化错误码 |
| AuditEvent | 固定元数据、结果、版本与耗时；无正文与凭据 |

SQLite 存实例、profile、desired/suppression、迁移与审计等低频元数据。高频帧、正文、原生登录态、长期完整 DOM 不入库；session/ticket 可内存保存，进程重启统一失效；lease 数据即便持久化也在启动时 fence/revoke，禁止恢复旧浏览器控制权。备份用一致性机制，不在 WAL 活跃时只复制一个 db 文件；备份加访问权限并设置审计保留/轮换策略（默认 30 天待操作者策略覆盖）。

### 10.2 外部 API 草案（协议设计，不是实现）

| 方法 / 路径 | 行为与权限 |
|---|---|
| GET /api/v1/apps、/instances、/instances/{id} | 管理 session；返回能力/版本/健康/blocked，不返回调试端口或账号凭证 |
| POST /api/v1/instances/{id}/operations | start/attach/stop-bridge/set-desired-state；CSRF、白名单动作、idempotency key；202 + operationId |
| GET /api/v1/operations/{id} | 状态与固定错误码；超时不虚构完成 |
| POST /api/v1/instances/{id}/leases | 原子获取独占 lease；占用 409；无隐式夺取 |
| POST /api/v1/leases/{id}/heartbeat；DELETE 同路径 | 续租/释放且校验当前 session、generation/fence |
| POST /__mmab/api/v1/instances/{id}/launch-tickets | 控制台端口；当前 lease 才可创建，目标端口由服务端决定；不接受任意 redirect URL |
| POST 实例端口 /__mmab/session/exchange | 唯一允许控制台跨端口提交的地址；原子单次消费票据，签发该端口专属 Cookie |
| POST /api/v1/auth/logout；实例 /__platform/logout | 撤销本作用域 session 与 transport；管理 logout 级联撤销 |
| GET /api/v1/events | 同源 SSE，状态元数据；断开不影响已授权 lease 的短期续租策略 |
| GET 原版资源；WSS 实例专用桥路径 | instance cookie、Host/Origin、lease/generation；只转发登记 adapter，不是开放代理 |

错误码至少区分 COMPATIBILITY_BLOCKED、PORT_OWNER_MISMATCH、WAIT_CONFIRM、LEASE_OCCUPIED、STALE_GENERATION、CODEC_UNSUPPORTED、QUEUE_LIMIT、INSPECTOR_CLEANUP_FAILED。REST 202 只代表接受操作，不代表原生窗口或页面已就绪。

### 10.3 adapter SPI 草案

Java 侧是可替换驱动接口，不加载不受信任插件到 JVM：

- describeProfile / discover：固定 App 事实与已知 profile，不扫描凭据。
- observe / probe(level)：返回实态与能力，固定 probe 集，不能执行任意表达式。
- planTransition(desired, observation)：返回需要确认的步骤与前提；不执行。
- start / attach / stopBridge / stopOwnedInstance：携带 operationId、expectedGeneration、deadline、确认许可；再次核对身份。stopOwnedInstance 不得作用于普通用户实例。
- openTransport / closeTransport：校验内部服务身份、lease/fence、codec/capability，建立有界 duplex；HTTP资源、sync bootstrap、upload 等非 WS 数据面同样受策略约束。
- collectCompatibility：生成候选 manifest 与测试结果，不能自动把候选变成已批准版本。

App-specific 实现保留 Qwen ipcMain/sendSync/trpc/主窗口镜像和 Codex renderer bindings/app-host/Git port 等差异；Java 调用固定操作，不从用户输入拼接 JS。SPI/协议采用显式版本协商，未知 major 拒绝连接。

### 10.4 建议仓库目录（已被 14.7 取代）

```text
minimac-app-bridge/
  server/                  # Spring modular monolith
    identity/ instances/ lifecycle/ leases/ proxy/ audit/ persistence/
  console/                 # Vue3 TS, management UI only
  adapters/
    qwen/                  # wrap current driver + in-process bridge + shim
    codex/                 # wrap current renderer CDP + shim + ports
    protocol/              # schemas, error codes, contract fixtures
  profiles/
    qwen/<version>/        # manifest, policies, compatibility evidence refs
    codex/<version>/
  tests/
    contract/ security/ fixtures/ lifecycle/ browser-acceptance/
  docs/                    # ADR, threat model, manual acceptance, migration
  packaging/               # future distribution and launchd templates
```

运行时 state/assets/profile 置于受权限保护的用户数据目录，不进 Git；现有两项目先保持原样、通过制品/profile 映射复用。两个适配器各自锁依赖，不跨仓引用 node_modules。原版资源及账号 profile 不纳入平台分发包。

## 11. 渐进落地与不扰动迁移

本节是后续实施/验收计划，本轮未执行；不虚构工期。

| 里程碑 | 范围 | 通过标准 / 失败去向 |
|---|---|---|
| M0 设计与证据冻结 | 本文、源文件 hash/版本清单、现有证据层级 | 评审接受边界、origin、粗粒度 native 权限和常开 CDP 风险；不满足则只观察，不实现 |
| M1 离线协议与安全骨架 | mock adapters + 固定 fixture 原版页面；不连正式 App | ticket 重放/过期/错误 origin、CSRF、cookie host 隔离、未知 operation、lease 竞争、旧 generation、codec 和队列测试通过。**状态：已完成（离线，2026-11-07），真实浏览器验证未做** |
| M2 观察模式管理 | Java/Vue 只发现和展示，原桥与旧 job 仍唯一 owner | 不开额外调试口、不发任务、不重载、不争客户端 lease；准确展示 actual-state 和证据缺口 |
| M3 内部认证与入口切换 | 经授权的受控维护窗口，封装现有 adapters，收敛旧外部入口 | Java-only 外部访问、adapter 拒绝未认证 loopback、精确 host/origin、单 lease；现有原生正常实例不被重启；若需重注入则等待批准，不后台强做 |
| M4 原版页面与版本验收 | 独立访问状态/端口，不窃取现有 web 客户端 | 每 app/version 分别完成 SETUP/AUTH/TRANSPORT/RENDER，原版 DOM非 setup，允许记录非阻断 CSP/console 项；700+ startup burst、第二 tab 不伤第一 tab、慢客户端/断线清理通过 |
| M5 受控业务与运维闭环 | 操作者明确授权的最小业务用例、恢复与回滚 | 千问新版发送/流式/取消；Codex一次最小任务及结果；任务影响预先说明。手动 stop suppression、版本变化blocked、端口冲突、App普通启动、确认框、恢复退避、双版本回退均有实态证据 |
| M6 可选嵌入与多节点 | iframe spike；随后独立评审 PG/远程 host agent | iframe 各安全/能力测试通过才发布 embed；跨节点需 mTLS、host enrollment、分布式 lease/fence、单 host恢复 owner；不沿用本地 loopback 假设 |

### 11.1 复用现有现场的验证纪律

- 先读现有证据，不为了完善报告复跑 live 测试；本轮已如此执行。现有 test 包含 live attach、临时 HTTPS 监听等行为，不能把“跑测试”误当无副作用只读操作。
- 后续先在 fixture/模拟 App 做协议和压力测试；真实验证选授权的原生专用实例/独立访问状态，不读取真实访问口令记录，不重置已有密码，不踢现有 tab，不打开/遍历历史正文。
- 如 Codex 当前已占有 web 客户端，应等待 lease 空闲或授权维护，不能再起另一个 fixture 连接后清掉共享 native ports；“独立 HTTPS 端口”不等于独立原生状态。
- DOM 验收采布尔值、数量、版本、错误类型即可，不采集账号、任务文本或编辑器内容。业务验证需明确授权并标明会在账号中留下测试记录。
- 跨端口开窗、Cookie 隔离与跨端口读取防护须在真实 Chrome、受信证书、实际 LAN IP 下验证；仅 localhost 通或 curl 成功不够。其他物理 LAN 设备是单独验收项。

### 11.2 切换与回滚门禁

迁移前保留原 job/profile/制品状态和恢复操作说明；先挂观察，再停旧恢复 owner，再接管。只在既定维护窗口调整旧暴露入口，不用防火墙/代理变更偷偷替代 adapter 自身认证。

切换后必须核对真实 listener owner、原生 PID/startTime、调试口暴露范围、旧 LAN 路径不可绕过、平台入口逐层验收；不是“Java 日志写 started”就结案。失败即 fence 新 lease、停止平台恢复、不破坏原生任务；确认无 in-flight 后还原旧 owner，明确旧路径安全能力仍是原 PoC 水平，不包装成无损安全回滚。

## 12. 风险、待决问题与默认值

| 风险 / 待决 | 默认决策 | 何时重新决策 |
|---|---|---|
| 新版 App 协议变化、私有 ipcMain、CSP/renderer 初始化变化 | manifest allowlist，未知版本 blocked；不自动注入/放宽协议 | 每次版本指纹变化，补离线及受控页面验收 |
| JDK/Boot 补丁与支持线 | JDK21/Temurin，Boot4.1 GA；具体补丁/EOL待锁 | 实施 kickoff 官方支持矩阵+制品/驱动兼容核定；不承诺未经核验的支持期限 |
| 入口形式与证书 | 同一 IP、每实例独立 HTTPS 端口；一张含 LAN IP 的证书 | 有内网 DNS 后可平移为子域名，获得更彻底的 Cookie 隔离 |
| 端口不隔离 Cookie | 每端口独立 Cookie 名、HttpOnly、精确 Origin、敏感资源仅同源可读 | 出现不可信应用或多操作者需求时重新评估 |
| iframe 权限与浏览器兼容 | NEW_TAB；当前 Codex 禁 framing | 独立 spike 全通过；不能仅去掉 XFO 就验收 |
| Codex app-host 写权限与常开 CDP | 单操作者粗粒度 native control，风险显式接受 | 若要求只读/不信任本机用户，禁用该 adapter 或重构更低权限通道，不能靠 getter 白名单兜底 |
| 多账号/多人同时操作 | 不支持；profile 不是安全账号容器；同实例一个 lease | 原生 App 隔离能力与 OS 凭据行为有实证后另立需求 |
| Java 全面替换 Node | 首先 wrap existing，逐步迁 HTTP/编排 | 只有等价契约/验收覆盖后才迁 CDP driver；目标进程与浏览器 JS仍保留 |
| 附件/本地协议/语音/MCP/Git命令 | 默认关闭未验证平台能力；不静默给空成功 | 每能力 threat model + 版本化契约 + 受控业务验收 |
| 更新中进程/文件稳定误判 | 稳定观察只是前置门槛，版本不匹配 blocked | 需要可靠 updater 状态来源再考虑更自动化 |
| 原生任务执行中连接丢失 | 显示状态未知，不重放写命令 | 未来若原协议提供真实幂等/取消/任务查询再增强 |
| 旧 Qwen scheduler 双拉起 / 旧公开入口绕过 | 观察期旧 owner唯一；切换有授权且单 owner | M3现场逐项确认；未关闭旧路径就不能标安全迁移完成 |
| 多节点平台 | 不做；单 Mac SQLite | 出现多个控制面写者、远程 host 或 HA 真实需求时引入PG/host agent与mTLS，不预建微服务 |

## 13. 评审应确认的技术决定

本方案无需先向操作者抛一串配置问题。现阶段按单 Mac、可信 LAN、单操作者、独占 lease、原版 UI 按独立端口新标签打开、Java 模块化单体+两本地 Node adapters 继续设计即可。后续实施前真正需要确认的是：实际端口分配与证书签发、Codex native control/CDP 风险是否可接受、切换旧 Qwen owner/外部入口的维护窗口，以及允许进行哪一个最小业务任务验收。

验收完成的定义是：**版本和权限边界可说明、控制面与数据面可撤销、恢复不与用户意图对抗、旧入口无法绕过、每一层有对应证据。** 不是“把两个端口放在 Vue 卡片里”，也不是“所有东西改成 Java”。

## 14. 扩展能力：接入更多应用

目标：以后接入第三个、第四个应用时，**新增一个适配器包和一份版本 profile**，不改 Java 核心、不改 Vue 控制台主体代码。千问和 Codex 作为首批两个“参考适配器”，同样按本节约定封装。

### 14.1 扩展分层：哪些能复用，哪些必须按应用实现

```text
+--------------------------------------------------------------------+
| 引桥核心（Java，所有应用共用，不随应用变化）                       |
| 身份/会话 · 端口开窗 · 租约 · 生命周期对账 · 代理 · 审计          |
| 健康分层 L0-L4 · 能力闸门 · 版本 manifest · 适配器注册与审批       |
+-------------------------------+------------------------------------+
                                | Adapter Protocol v1（版本协商）
+-------------------------------v------------------------------------+
| 桥接策略族（Strategy，少量、平台维护）                              |
|  S1 electron-main-inject    主进程注入 ipcMain        ← 千问        |
|  S2 electron-renderer-cdp   renderer CDP + 端口中继   ← Codex       |
|  S3 local-web-proxy         应用自带本地 Web UI，仅需鉴权代理       |
|  S4 official-remote-api     应用官方远控/服务接口 + 平台提供前端     |
+-------------------------------+------------------------------------+
                                | 复用 MMAB Adapter SDK
+-------------------------------v------------------------------------+
| 应用适配器（Adapter，每个应用一个，按版本出 profile）               |
|  启动/识别规则 · 注入载荷 · shim 差异 · 消息过滤 · codec · 探针     |
+--------------------------------------------------------------------+
```

- **策略族**是平台级能力，数量少、变化慢、由平台评审；**适配器**只描述某个应用如何套用某个策略，变化快、按应用版本迭代。
- 新应用优先归入已有策略族；只有出现全新接入机制时才新增策略族，并需独立安全评审。
- 不纳入：截图流远控（已被否决）；既无法注入、又不提供调试/官方接口的原生应用。这类应用在接入评估中直接判为“不可桥接”，不硬做。

### 14.2 Adapter SDK：从两个桥中提取的公共件

两个现有桥里已经有大量可复用、且踩过坑的代码。SDK 把它们固化下来，新适配器直接引用：

| SDK 模块 | 来源与已验证的经验 |
|---|---|
| secure-context 补齐 | 千问 http 下 `crypto.randomUUID` 白屏、`navigator.mediaDevices` 切换崩溃；统一提供 randomUUID / SHA-256 digest / clipboard.writeText / mediaDevices 占位（不给 getUserMedia） |
| preload 同世界兼容 | 千问 2.0.0 只读启动快照重复暴露导致启动失败；contextBridge 适配器只对同值只读属性放行 |
| 结构化类型 codec | 千问 Buffer/Map/Set/Date/BigInt/Error/undefined；Codex ArrayBuffer/typed array/嵌套 MessagePort；统一编号与类型标记，未支持类型显式报错 |
| MessagePort 中继 | Codex 嵌套端口、端口 ID 隔离、断开清理 |
| 有界调度队列 | Codex 启动 600+ 帧被旧限流误杀的教训；字节/帧/待决数多维上限 + 背压 |
| 启动引导白名单 | Codex `request-app-server-state` 缺失导致空壳；适配器需声明“必须转发的启动消息”并有测试 |
| 鉴权页与 Origin 规则 | `Referrer-Policy: same-origin`，拒绝 null Origin；不再使用 no-referrer |
| 可见错误提示 | 断线、标签占用、启动超时必须可见，禁止静默白屏 |
| 进程身份核验 | PID + 启动时间 + 可执行文件 + profile + 端口所有者，防误接正常实例 |

SDK 分两部分：浏览器侧/应用内 JS（必须是 JS），以及适配器驱动侧（首版 Node，后续可提供 Java 驱动实现）。

### 14.3 适配器包规范

每个适配器是一个独立、可校验的包，平台不在 JVM 内加载第三方代码，适配器以**独立进程**运行，通过 Adapter Protocol 与 Java 通信。

```text
adapters/<app-id>/
  adapter.yaml          # 元数据、策略族、所需权限、风险等级、协议版本
  driver/               # 适配器驱动进程（发现、启动、接入、探针、传输）
  payload/              # 注入到应用内的 JS（主进程或 renderer）
  shim/                 # 浏览器侧兼容层差异（基于 SDK）
  policy/               # 消息过滤、启动白名单、禁用通道
  profiles/<version>/   # 每个应用版本一份 manifest 与验收证据引用
  tests/                # 契约测试、fixture、启动突发测试
```

`adapter.yaml` 必须声明的关键字段：

| 字段 | 示例 / 说明 |
|---|---|
| appId / 显示名 | `qwenwork` / 千问办公 |
| strategy | `electron-main-inject`、`electron-renderer-cdp` 等 |
| 应用识别 | bundleId、可执行路径白名单、版本读取方式 |
| 所需权限 | 是否需启动参数、是否需临时 inspector、是否需**常开** CDP、是否需文件上传目录 |
| 风险等级 | 例如“CDP 常开：本机其他进程可控制应用” |
| 能力声明 | 见 14.4，每项带验收状态 |
| 资源来源 | 从安装包提取 / 应用本地 URL / 适配器自带 |
| 协议版本 | 支持的 Adapter Protocol 版本范围 |
| 包校验 | 包 hash 或签名；平台只运行已登记的包 |

**新适配器首次启用需操作者在控制台审批**，审批页明示所需权限和风险等级。适配器拿不到平台口令、会话 Cookie 或开窗票据。

### 14.4 统一能力模型

平台定义标准能力 ID，适配器按应用版本声明状态。控制台、网关和验收都以此为准：

| 能力 ID | 含义 |
|---|---|
| `ui.render` | 原版界面可加载 |
| `ui.history` | 历史会话浏览 |
| `ui.compose` | 新建任务/发送消息 |
| `stream.push` | 宿主推送、流式结果 |
| `file.upload` / `file.drop` | 附件、拖放 |
| `clipboard.write` / `clipboard.read` | 剪贴板 |
| `media.voice` | 语音（需 HTTPS） |
| `app.git` | Git 类操作（Codex） |
| `app.extension` | 插件/MCP 类能力 |

状态只有四种：**SUPPORTED（有当前版本业务证据）/ UNVERIFIED / DISABLED / UNSUPPORTED**。未验收能力默认关闭，网关拒绝对应通道，界面显示“未验证”，不再出现“看起来能用、实际静默失败”。

### 14.5 Java 侧扩展点

| 扩展点 | 作用 |
|---|---|
| `StrategyDriver` | 策略族的通用生命周期编排（启动、接入、重建 generation） |
| `AdapterClient` | 与适配器进程的认证通道和协议协商 |
| `HealthProbe` | L0-L4 分层探针，适配器可补充应用专属探针 |
| `CompatibilityChecker` | 根据指纹匹配 profile，不匹配即阻塞 |
| `ResourceProvider` | 原版资源来源（安装包提取、本地 URL 代理等） |
| `TransportPolicy` | 每个应用的帧预算、消息过滤、能力闸门 |
| `ConsoleDescriptor` | 向 Vue 控制台提供该应用的展示信息、专属配置表单 |

控制台按 `ConsoleDescriptor` 动态渲染应用卡片、配置项和能力清单，新增应用不需要改 Vue 页面代码。

### 14.6 新应用接入流程

```text
1 可行性探测 → 2 选择策略族 → 3 生成适配器骨架 → 4 离线契约测试
      ↓                                              ↓
  不可桥接则结束                         5 独立实例验证（不碰正式实例）
                                                     ↓
                    7 审批启用 ← 6 分层验收 L0-L4 并登记 profile
```

1. **可行性探测**：平台提供只读探测工具，输出是否 Electron、fuses（含改名框架的 sentinel 读取）、preload 暴露面、IPC 通道、CSP、自定义协议、是否有官方远控接口。
2. **选择策略**：主进程可 inspect 优先 S1；否则 renderer CDP 可用走 S2；应用自带本地 Web UI 走 S3；有官方接口且需要稳定性时可选 S4。
3. **骨架生成**：按策略模板生成 `adapter.yaml`、shim、payload、测试目录。
4. **离线契约测试**：codec、启动突发、端口生命周期、鉴权与 Origin、第二标签占用。
5. **独立实例验证**：独立 profile / 端口，不重启、不接管用户正在使用的实例。
6. **分层验收**：SETUP、AUTH、TRANSPORT、RENDER、BUSINESS 分别记录，业务层需操作者授权。
7. **审批启用**：登记 profile，能力按证据开启。

### 14.7 新增实体与目录调整

数据模型在第 10.1 节基础上增加：

| 实体 | 关键字段 |
|---|---|
| AdapterPackage | appId、strategy、版本、包 hash/签名、所需权限、风险等级、审批状态 |
| StrategyFamily | 策略 ID、协议版本、安全评审状态 |
| CapabilityDefinition | 能力 ID、说明、依赖（如 media.voice 依赖 HTTPS） |
| CapabilityStatus | profile × 能力 → 状态 + 证据引用 |

第 10.4 节目录相应调整为：

```text
minimac-app-bridge/
  server/                 # Java 核心
  console/                # Vue 控制台
  sdk/
    web/                  # 浏览器/应用内 JS 公共件
    driver-node/          # 适配器驱动 SDK（首版）
    protocol/             # Adapter Protocol schema、错误码、契约 fixture
  strategies/             # 策略族模板与通用测试
  adapters/
    qwenwork/             # 参考适配器 1
    codex/                # 参考适配器 2
  tools/
    probe/                # 只读可行性探测
    scaffold/             # 适配器骨架生成
  tests/  docs/  packaging/
```

### 14.8 扩展相关的里程碑补充

- 在 M1 同步定义 Adapter Protocol v1、能力模型和 `adapter.yaml` 规范（已完成，见 `sdk/protocol/`）。
- 在 M3 把千问、Codex 改造成符合规范的参考适配器，并以“两个应用无需改核心即可切换”为验收条件。
- 新增 **M7 第三应用试接入**：用探测工具 + 骨架完成一个新应用的独立实例验证，核心代码零修改作为扩展性验收标准。

## 15. 平台命名

**正式名称（2026-11-07 确定）**

- 英文：**MiniMacAppBridge**
- 中文：**miniMac App 引桥**

含义：引桥是把车辆从地面道路引上主桥的那一段。这里把 Mac 上只能本机使用的桌面应用，引到浏览器这条“主路”上；应用本身不改、不重写，平台只负责接入、鉴权和通道。名称中的 Mac 与当前部署范围一致（单台 Mac 宿主）；将来若扩展到其他系统宿主，品牌名可保持不变，宿主类型在 Host 实体中区分。

| 用途 | 命名 |
|---|---|
| 仓库 / 目录 | `minimac-app-bridge` |
| Java 包名 | `*.minimacappbridge.*`（全小写，Java 包名约定） |
| 简称（日志前缀、配置键、指标前缀） | `mmab`，例如配置 `mmab.server.port`、日志 `[mmab]` |
| Maven 坐标 | `artifactId`：`mmab-server`、`mmab-adapter-sdk` 等 |
| 控制台 | miniMac App 引桥 控制台（MMAB Console） |
| 适配器 / SDK / 协议 | MMAB Adapter、MMAB Adapter SDK、MMAB Adapter Protocol |
| 访问地址示例 | 控制台 `https://<LAN-IP>:8443/`，千问 `:8444`，Codex `:8445`，新应用 `:8446-8459`；平台保留路径前缀 `/__mmab/` |

正式使用前，仍需在公司内部检索是否存在同名项目。
