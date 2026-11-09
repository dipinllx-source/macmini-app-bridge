# Design

## Context

动机见 proposal.md。与本设计相关的现状（2026-11-09，main@d301ac9）：

- **会话**：`SessionService` 全在内存；过期判定为 `created + maxAge` 或 `lastSeen + idle`（默认 8h / 30m）；会话 Cookie 无 `Max-Age`（浏览器关闭即丢）；实例会话依附控制台会话，控制台会话闲置失效会连带撤销实例会话，而实例页的心跳只续租约、不续父会话。
- **数据面**：浏览器 `transport.js` 每 15 s 发 HEARTBEAT（续租约）；断开后只显示横幅，不自动重连。平台重启即丢全部会话 / 租约。
- **实测断线**：2026-11-09 上午 codex-01 同一会话约每 5 分钟出现一次 `transport.close(OK)`，并在 1 秒内以同一实例会话重新 `transport.open`。结果码 OK 表示浏览器侧先关闭；1 秒内重连说明页面被自动重新加载（transport 自身不重连，横幅按钮需人工点击）。触发方尚未确定（候选：Codex 原版页面自身的 reload 逻辑、app-host 连接变化、浏览器后台策略）。
- **生命周期**：`lifecycle`、`persistence` 包仅为占位；`InstanceRegistry` 只在启动时拉起适配器，适配器退出即 `ADAPTER_UNAVAILABLE`。千问注入（`adapters/qwenwork/tools/inject.mjs`）与 Codex 专用实例启动（`adapters/codex/tools/launch.mjs`）都是手工工具。运行入口是手写的 `~/.mmab/start.sh`。
- **旧原型**已停用；旧 LaunchAgent `com.qwenwork.native-web` 已 disable（其“精确 --inspect + 信号证明后 SIGUSR1 重开”的安全规则沿用）。

## Goals / Non-Goals

**Goals:**
- 页面在无人操作时可连续数小时不掉线；瞬断自动恢复；7 天内（可配置）免重复登录。
- 电脑重启后无需人工即可恢复两个实例；无法安全恢复时停在“需确认”并可一键处理。
- 控制台成为唯一的运行状态视图。

**Non-Goals:**
- 不自动适配应用升级；不做跨设备同时操作同一实例（单租约不变）；不改变 Adapter Protocol v1 的线路格式。

## Decisions

### D1. 先查明 5 分钟周期断开，再做自动重连
自动重连会掩盖周期断开（页面每 5 分钟整体刷新一次仍是可感知的中断，且会丢失页面内未保存状态）。第一步只加诊断：浏览器在关闭 / 卸载时把 `{closeCode, wasClean, navigationType, visibilityState, pageReloadedBy}` 经 `navigator.sendBeacon` 上报到实例端口的固定诊断端点（只收枚举与数字），平台写审计；同时在 shim 中记录 `location.reload` / `beforeunload` 的调用栈摘要（仅文件名 + 行号）。定位后再决定修复点（例如拦截原版页面的整页 reload、修正某个被丢弃的宿主请求）。
- 备选：直接上自动重连。否决理由：会把“整页刷新”误当作已解决。

### D2. 可恢复的数据连接：挂起 + 同页恢复 + 受控重新初始化
- **平台侧挂起**：浏览器连接在没有关闭帧（网络中断、1006）时不立即关闭，而是挂起 40 s（`RESUME_GRACE`，小于租约 60 s，挂起期间平台续租约）：端口表、原生侧状态（千问 tRPC 订阅、Codex app-host 端口）与出站缓冲都保留。页面以 1000 / 1001 正常关闭时立即正式关闭。
- **同页恢复**：每条连接的第一帧 `HELLO{connectionId, resumed}`；页面断线后以 `?resume=<connectionId>` 重连，会话、租约绑定与 generation 都未变时换上新传输，缓冲的事件继续送达——应用状态无损。
- **受控重新初始化**：超过宽限期、平台重启、租约 / generation 变化时，新连接 `resumed:false`，页面的订阅与端口已失效，shim 执行一次受控整页刷新（会话持久，无需登录），审计记录原因。
- **重连前探测**：WebSocket 握手失败不暴露原因，页面先 `GET /__mmab/bridge/status`：200 重连；401 提示重新登录；409 提示已在别处打开；503 + 升级受阻提示；其他继续退避（1 s 起，上限 15 s，页面隐藏时暂停，可见时立即重试）。
- 在途请求在断开时以 `DEADLINE_EXCEEDED`（状态未知）拒绝，绝不重放；断开期间新发起的请求排队，重连后发送。
- 备选：只做“重连即整页刷新”。否决理由：每次网络抖动都会丢失页面状态（例如未发送的输入），不满足连续性规格。

### D3. 会话：滑动续期 + 硬上限 + 持久 Cookie + 父会话联动
- `session-max-age` 默认 `7d`；`session-idle` 默认等于 `session-max-age`（可配置更短）。
- 实例会话的任何活动（请求、心跳、资源访问）同时 `touch` 父控制台会话；父会话 `lastSeen` 写库做节流（每 5 分钟最多一次），避免心跳造成写放大。
- 会话 Cookie 加 `Max-Age = 会话剩余期限`，仍保持 `__Host-`、Secure、HttpOnly、SameSite=Strict、Path=/。
- 备选：刷新令牌（refresh token）机制。否决理由：单操作者场景下复杂度不值得；持久 Cookie + 服务端散列已能满足“可撤销”。

### D4. 持久化：SQLite（`mmab.data-dir/mmab.db`），只存必要元数据
- 表：`console_session(id, token_hash, csrf_hash, created_at, last_seen, revoked)`、`instance_session(id, parent_id, instance_id, port, token_hash, created_at, last_seen, revoked)`、`audit(at, actor, action, instance, result, detail)`、`instance_state(instance_id, desired, actual, reason, app_version, updated_at)`。
- 不存：租约、票据、明文令牌、口令、负载。平台启动时清空租约（内存态），实例会话保留但其 `lease/generation` 绑定作废——重连时由平台为该会话重新签发租约（无需新票据，因为实例会话本身已经过票据交换认证）。
- 访问方式：Spring JDBC + 版本化迁移（自带简单迁移表，不引入 ORM）；数据库操作在有界的 boundedElastic 执行器上执行，不阻塞事件循环；WAL 模式。
- 备选：继续纯内存 + 文件快照。否决：审计查询与会话撤销都需要事务语义。

### D5. 生命周期：平台内的对账循环（reconcile），驱动“动作”复用现有工具逻辑
- 每个实例一个对账任务：观测（L0 进程 / L1 端口 / L2 适配器链路 / 版本）→ 与期望状态比较 → 决定动作（`START_DEDICATED`、`LAUNCH_QWEN`、`INJECT`、`WAIT_CONFIRM`、`BLOCKED_UPGRADE`、`BACKOFF`）→ 执行 → 写 `instance_state` 与审计。周期 15 s，事件触发时立即执行。
- 动作实现：在适配器包里新增固定的 Node 入口（`tools/recover.mjs <action>`，内部复用 `inject.mjs` / `launch.mjs` 的核验逻辑），由平台以静态命令白名单调用，不接受 API 传入的参数。这样“进程身份核验”的唯一实现仍在适配器内。
- 千问启动：`open -a QwenWorkCN --args --inspect=127.0.0.1:9229`，就绪后注入并关闭 inspector。普通方式运行 → `WAIT_CONFIRM`；操作者确认后请求正常退出（AppleScript quit，需操作者在 Mac 上点确认），等待退出后再按可接入方式启动。
- 重新注入：仅在“精确 `--inspect` 参数 + 信号证明与当前 Electron Framework 散列一致”时允许 SIGUSR1；证明文件迁移到 `mmab.data-dir/qwenwork/signal-proof.json`（从旧位置一次性复制）。
- 退避：失败后 30 s / 2 min / 10 min，连续 3 次失败进入 `RECOVERY_FAILED`，环境变化（进程 / 版本 / 端口变化）或操作者操作后复位。
- 备选：继续使用独立 LaunchAgent 检查脚本。否决：状态无法进控制台、与平台租约 / 代际不一致。

### D6. 平台常驻：用户级 LaunchAgent，只负责平台进程
- `packaging/launchd/io.github.dipinllx.mmab.plist.in`：`RunAtLoad`、`KeepAlive{SuccessfulExit=false}`（显式正常退出不拉起）、`ThrottleInterval 30`；程序为平台启动脚本（读取 `mmab.data-dir/mmab-local.yml`，含 TLS、绑定地址、实例配置），口令散列从 0600 文件读取。
- 安装 / 卸载脚本幂等；安装前检查不存在其他拉起千问或 Codex 的 job（旧 `com.qwenwork.native-web` 必须为 disabled）。
- 内网 IP 变化：启动脚本取当前 en0 地址；证书 SAN 不含新 IP 时重签并在控制台提示“证书已更换，需重新信任”。

### D7. 控制台：只读状态 API + SSE
- `GET /__mmab/api/v1/instances`（已有）扩展为包含分层健康、原因码、版本信息；新增 `GET /__mmab/api/v1/events`（SSE，同源、会话鉴权）推送状态变化；`POST /__mmab/api/v1/instances/{id}/actions/confirm-restart`（CSRF + 二次输入口令，见设计 §5.4 敏感操作）；`POST /__mmab/api/v1/auth/revoke-all`。
- Vue 页面替换 mock：总览、实例详情（分层健康 + 最近审计）、生命周期（期望 / 实际 / 动作历史）、版本兼容、安全与审计（分页查询）。

## Risks / Trade-offs

- [持久会话被盗用 7 天有效] → HttpOnly + `__Host-` + 精确 Origin 已限制窃取面；控制台提供“注销所有会话”；会话列表显示最近使用时间与来源 IP 段，便于发现异常。
- [平台启动千问时操作者正好在用别的东西] → 只在千问未运行时启动；运行中的普通实例只提示不动。
- [SIGUSR1 在未证明的千问新版本上可能终止进程] → 继续以 Framework 散列为证明前提；版本变化后证明失效，进入 `WAIT_CONFIRM` 由操作者重启接入。
- [Codex 页面热重连不可行，只能整页刷新] → D2 退化为受控刷新并审计；这仍是“不需人工”的恢复，但会丢失页面临时状态，作为已知限制记录。
- [SQLite 写放大 / 损坏] → 心跳续期节流写；WAL；数据库位于本机数据目录（非网络盘）；损坏时会话丢失只导致重新登录，不影响安全。
- [5 分钟断开根因可能在应用自身，无法消除] → 若确认为原版页面行为，则在 shim 中以最小拦截处理并以测试覆盖；若无法安全拦截，则在验收中如实记录。

## Migration Plan

1. 停用手写入口：首次安装 LaunchAgent 前 `~/.mmab/stop.sh`；把 `~/.mmab` 中的 TLS 证书、口令文件迁移到 `mmab.data-dir`（保留指纹，避免重新信任）。
2. 确认旧 `com.qwenwork.native-web` 为 disabled 且未加载。
3. 安装平台 LaunchAgent；观察一次完整的“注销登录 / 重启电脑”恢复。
4. 回滚：卸载平台 LaunchAgent，恢复 `~/.mmab/start.sh` 手工启动（配置向后兼容；数据库可直接删除，代价是重新登录）。

## Open Questions

- 周期断开的根因（由 D1 的诊断数据回答；不影响本变更的任务划分，只影响 D2 中 Codex 是否需要受控整页刷新）。
