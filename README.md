# MiniMacAppBridge · miniMac App 引桥

把 Mac 上只能本机使用的桌面应用（Electron 等），以**原版界面**的形式安全地开放给可信内网中的浏览器使用。

应用本身不改、不重写，也不是截图流远程桌面：浏览器运行应用自己的 HTML/CSS/JS，平台负责接入、鉴权、通道、生命周期和审计。

> **当前状态：设计阶段 + 工程骨架。**
> 本仓库还**没有**任何桥接实现，不能用于远程访问。代码只包含平台骨架、协议草案和 mock 数据的控制台。
> 完整设计见 [docs/design.md](docs/design.md)。

## 定位

| 统一的部分（平台核心，Java） | 按应用实现的部分（适配器） |
|---|---|
| 身份与会话、开窗票据、独占租约 | 应用识别与启动方式 |
| 生命周期对账、故障恢复、版本兼容门禁 | 注入到应用内的 JS（主进程或 renderer） |
| HTTP/WS 代理、有界队列、审计 | 浏览器侧 shim 差异、消息过滤、数据编码 |
| 能力开关、分层健康（L0–L4） | 应用专属健康探针 |

首批参考适配器：千问（主进程注入）、Codex（renderer CDP）。后续应用按 [docs/design.md §14](docs/design.md) 的适配器规范接入，**不改平台核心**。

## 架构

```text
              可信内网浏览器（单操作者）
             /                          \
  https://<LAN-IP>:8443/        https://<LAN-IP>:8444/ (qwen-01)
  Vue 控制台                     https://<LAN-IP>:8445/ (codex-01)
  实例 / 健康 / 审计              原版应用界面 + 最小 shim
             |                          |
  +----------v--------------------------v-----------+
  | mmab-server（一个 Java 进程，多 HTTPS 监听）      |
  |  控制面：身份、实例、租约、生命周期、审计         |
  |  数据面：资源代理、WS 转发、会话校验、背压        |
  +----------------+---------------------+-----------+
                   | MMAB Adapter Protocol（内部认证）
         +---------v--------+   +--------v----------+
         | 千问适配器 (S1)   |   | Codex 适配器 (S2)  |
         | 主进程注入 JS     |   | renderer CDP + 端口 |
         +------------------+   +-------------------+
```

## 端口布局（建议值）

| 端口 | 用途 |
|---|---|
| 8443 | 控制台 + 平台 API（`/__mmab/...`） |
| 8444 | qwen-01 |
| 8445 | codex-01 |
| 8446–8459 | 预留给后续接入的应用实例 |

同一 IP、每实例独立端口：不依赖 DNS/hosts，原版应用在根路径运行。端口隔离页面存储与脚本，**但不隔离 Cookie**，加固规则见 [docs/design.md §5.2](docs/design.md)。

## 技术栈

- 后端：JDK 21、Spring Boot 4.1.2（WebFlux / Reactor Netty）、Spring Security、SQLite（单机）
- 控制台：Vue 3、TypeScript、Vite、Pinia、Vue Router、Element Plus
- 适配器：Node.js + 必要的应用内 JS（Electron 的 ipcMain / MessagePort 只能由 JS 承接，见 [ADR-0003](docs/adr/0003-keep-js-adapters.md)）

## 目录

```text
pom.xml                      Maven 根（Java 21，Spring Boot parent）
server/
  mmab-server/               平台服务（目前仅健康接口与配置骨架）
  mmab-adapter-protocol/     适配器协议：信封、错误码、能力四态、策略族
console/                     Vue 控制台（mock 数据）
sdk/
  web/                       浏览器 / 应用内 JS 公共件（规划）
  driver-node/               适配器驱动 SDK（规划）
  protocol/                  adapter.yaml 与 envelope 的 JSON Schema 草案
strategies/                  策略族模板（规划）
adapters/
  qwenwork/                  参考适配器 1：描述文件草案
  codex/                     参考适配器 2：描述文件草案
tools/probe/                 只读可行性探测（规划）
tools/scaffold/              适配器骨架生成（规划）
tests/  packaging/           契约 / 安全 / 生命周期测试与打包（规划）
docs/                        设计文档与 ADR
```

## 构建

```bash
# Java（需 JDK 21+、Maven 3.9+）
mvn -B verify

# 控制台（需 Node.js 20.19+ 或 22.12+）
cd console && npm ci && npm run build

# 本地运行骨架服务：仅监听 127.0.0.1:8443，无 TLS、无鉴权
mvn -pl server/mmab-server spring-boot:run
curl http://127.0.0.1:8443/__mmab/api/v1/health
```

## 里程碑

| 阶段 | 内容 |
|---|---|
| M0 | 设计与证据冻结（本仓库当前所处阶段） |
| M1 | 离线协议与安全骨架：mock 适配器、票据、租约、Origin/CSRF、codec、队列测试 |
| M2 | 观察模式：只发现和展示实例，不接管 |
| M3 | 内部认证与入口切换：两个参考适配器按规范封装 |
| M4 | 原版页面与版本验收（SETUP / AUTH / TRANSPORT / RENDER） |
| M5 | 受控业务验收与运维闭环（恢复、升级阻塞、回滚） |
| M6 | 可选：iframe 嵌入、多节点 |
| M7 | 第三应用试接入：核心代码零修改 |

## 安全边界

- 面向**单操作者、可信内网、自有应用**；不是多账号隔离，也不是只读沙箱——网页端拥有与本机应用相同的账号权限。
- 平台口令与应用账号无关；应用账号登录只在原生窗口由本人完成。
- 端口不隔离 Cookie：每端口独立 `__Host-` Cookie 名、HttpOnly、精确 Origin 校验、敏感资源仅同源可读。
- 部分策略族（如 renderer CDP）需要本机调试端口常开，本机其他进程理论上可控制该应用；启用前需显式接受该风险。
- 不读取、不迁移应用账号凭证；不改应用安装包、签名或 fuses；不分发应用原版资源。

## 许可

尚未选择开源许可证。在添加 LICENSE 之前，默认保留所有权利。
