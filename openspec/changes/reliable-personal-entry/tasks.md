# Tasks

## 1. 断线诊断（D1，先于自动重连）

- [x] 1.1 浏览器侧在 WS 关闭 / 页面卸载时经 `sendBeacon` 上报 `{closeCode, wasClean, navigationType, visibilityState}` 与 reload 触发方摘要（仅文件名:行号）到实例端口的固定诊断端点；平台校验字段为枚举 / 数字后写审计；验证：`sdk/web` 单测覆盖字段白名单，Java 测试覆盖端点只收白名单字段且需实例会话
- [ ] 1.2 在本机让 codex-01 与 qwen-01 各空闲打开 ≥ 30 分钟，收集诊断审计，定位约 5 分钟一次断开的触发方并写入 `docs/m4-reliability-findings.md`；验证：文档给出触发方证据与复现条件
- [ ] 1.3 按 1.2 的结论在 shim / 适配器中消除周期断开（或确认不可消除并记录）；验证：codex-01 空闲 2 小时审计中无 `transport.close`

## 2. 会话期限与持久化（identity/operator-session）

- [x] 2.1 引入 SQLite（`mmab.data-dir/mmab.db`，WAL）、Spring JDBC 与版本化迁移，建 `console_session`、`instance_session` 表；验证：迁移单测在临时目录建库、重复启动幂等
- [x] 2.2 `session-max-age` 默认 7d、`session-idle` 默认等于 max-age 且可配置；会话以 token 散列落库，平台重启后仍有效；验证：`SessionServiceTest` 覆盖默认 / 自定义期限、重启后恢复、库中无明文令牌
- [x] 2.3 实例活动（请求 / 心跳 / 资源）续期父控制台会话，落库续期节流 5 分钟；验证：测试中只用实例连接 31 分钟后两类会话仍有效，写库次数符合节流
- [x] 2.4 会话 Cookie 加 `Max-Age=剩余期限`，其余属性不变；验证：E2E 断言 Set-Cookie 属性
- [x] 2.5 平台启动作废全部租约与票据；已持久化的实例会话重连时重新签发租约与 fencing token；验证：E2E 重启平台后旧租约被拒、同会话重连成功
- [x] 2.6 `POST /__mmab/api/v1/auth/revoke-all`（CSRF + 口令复核）撤销全部会话并关闭数据连接；验证：E2E 撤销后各页面连接收到会话失效关闭码
- [x] 2.7 更新 README / ADR（新增 ADR-0006 会话与持久化决策）与 `application.yml` 注释；验证：文档中的配置键与代码一致（`MmabPropertiesTest`）

## 3. 自动重连（transport/connection-continuity）

- [ ] 3.1 `transport.js` 按关闭码分流：可重试类指数退避重连（1 s 起、上限 15 s，页面隐藏时暂停），不可重试类显示对应提示并停止；验证：`web-m3.test.mjs` 增加 1006 / 1011 / 平台不可达重连成功、1008 会话失效与 1013 占用不重试的用例
- [ ] 3.2 在途请求在断开时以状态未知拒绝，重连后不重放；验证：单测断言重连后线路上没有重复的 REQUEST
- [ ] 3.3 重连后端口中继重建：千问 shim 无需处理；Codex shim 触发原版页面重新建立 app-host 连接，不可行时执行受控整页刷新并上报原因；验证：离线测试覆盖两种路径，codex-01 实机断网 10 秒后自动恢复
- [ ] 3.4 平台为可恢复的关闭发送明确关闭码（1012 重启、1011 适配器重启），不可恢复的发送 1008 + 原因码；验证：Java E2E 断言各场景关闭码

## 4. 持久审计（audit/audit-log）

- [ ] 4.1 审计写入 `audit` 表（异步、有界队列，满时丢弃并计数），保留期可配置默认 30 天并定时清理；验证：测试覆盖重启后可查询、超期清理、队列满不阻塞请求路径
- [ ] 4.2 审计字段白名单：detail 只允许固定格式键值（大小、计数、关闭码、原因码）；验证：测试注入含负载的 detail 被拒绝或截断

## 5. 开机恢复（lifecycle/boot-recovery）

- [ ] 5.1 实现 `lifecycle` 对账循环与 `instance_state` 落库（期望 / 实际 / 原因 / 版本），15 s 周期 + 事件触发，带 30 s / 2 min / 10 min 退避与 3 次失败熔断；验证：用 mock 适配器的单测覆盖状态迁移、退避与熔断复位
- [ ] 5.2 适配器新增静态恢复入口 `adapters/codex/tools/recover.mjs`（`status` / `start-dedicated`），复用 `launch.mjs` 核验；平台以命令白名单调用；验证：离线测试 + 实机：停止专用实例后 1 分钟内被平台拉起，正常 Codex 不受影响
- [ ] 5.3 `adapters/qwenwork/tools/recover.mjs`（`status` / `launch-inspect` / `inject` / `quit-for-confirm`），复用 `inject.mjs` 核验；信号证明迁移到 `mmab.data-dir/qwenwork/`；验证：离线测试覆盖“普通启动→WAIT_CONFIRM 不动”“精确参数+证明→重新注入”“证明不符→WAIT_CONFIRM”
- [ ] 5.4 控制台“重启并接入”动作（CSRF + 口令复核）：请求千问正常退出、等待退出、可接入方式启动并注入；验证：实机一次完整流程，审计记录每一步
- [ ] 5.5 平台 LaunchAgent 模板与幂等安装 / 卸载脚本（`packaging/launchd/`），启动脚本读取 `mmab.data-dir` 下的本机配置、处理 IP 变化重签证书；安装前校验旧 `com.qwenwork.native-web` 已禁用；验证：脚本测试 + 实机注销登录后平台自动启动
- [ ] 5.6 文档：`docs/operations.md`（安装、停止、回滚、证书、千问需由平台启动的约定）；验证：按文档在干净的 `mmab.data-dir` 上完成安装

## 6. 控制台真实状态（console/instance-health）

- [ ] 6.1 实例 API 扩展分层健康（L0–L4 状态与验证时间）、原因码、版本信息；新增 SSE `GET /__mmab/api/v1/events`（同源、会话鉴权、心跳）；验证：Java 测试覆盖字段、未验证层不报告正常、SSE 鉴权
- [ ] 6.2 版本不符检测：Codex 资源版本与已安装版本、千问框架散列 / 版本与适配器 profile 不一致时进入 `BLOCKED_UPGRADE` 并拒绝开窗；验证：测试用伪造版本触发阻塞，开窗返回同一原因码
- [ ] 6.3 Vue 总览、实例详情、生命周期、版本兼容、安全与审计五页改用真实 API + SSE，删除 mock；“需确认”显示“重启并接入”，安全页提供“注销所有会话”；验证：`npm run build` 通过，无头浏览器检查各页无 mock 标记且状态变化 10 s 内刷新

## 7. 集成验收

- [ ] 7.1 重启电脑后不做任何手工操作：平台、Codex 专用实例、千问（平台启动）全部恢复，控制台两实例均为可用；记录到 `docs/m4-acceptance.md`
- [ ] 7.2 两个实例页面空闲 8 小时不断开、断网 30 秒自动恢复、7 天内重启浏览器免登录；记录到 `docs/m4-acceptance.md`
- [ ] 7.3 模拟应用升级（改资源版本）后控制台显示升级受阻，恢复资源后自动转为可用；记录到 `docs/m4-acceptance.md`

## Workflow follow-up

- 全部任务完成并通过验收后归档本变更（`openspec archive reliable-personal-entry`），核对 `openspec/specs/` 下生成的 5 个主规格。
