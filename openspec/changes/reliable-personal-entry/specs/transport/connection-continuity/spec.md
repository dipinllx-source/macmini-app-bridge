# Spec Delta

## Purpose

保证操作者在浏览器中打开的应用实例页面可以长时间保持可用：连接被动中断时自动恢复，不重放可能已执行的操作，确实无法恢复时明确告知原因。

## ADDED Requirements

### Requirement: Idle page stays connected
平台 SHALL 在浏览器页面保持打开且操作者会话有效期间维持实例数据连接，不得因空闲、定时器或平台侧的周期性行为主动断开连接。

#### Scenario: Page left open without interaction
- **WHEN** 操作者打开实例页面后 2 小时内不做任何操作，且会话与租约始终有效
- **THEN** 数据连接在整个期间不发生平台侧断开，审计中该连接没有 `transport.close` 记录

#### Scenario: Unexplained periodic disconnect is diagnosable
- **WHEN** 浏览器侧连接关闭
- **THEN** 平台记录关闭发起方（浏览器 / 平台 / 网络）、关闭码与页面是否随之重新加载，且不记录消息内容

### Requirement: Automatic reconnection after transient loss
浏览器页面 SHALL 在网络瞬断、平台短暂不可用或页面被系统挂起后恢复时，使用原会话自动重新建立数据连接，无需操作者刷新页面或回到控制台。

#### Scenario: Network blip
- **WHEN** 数据连接因网络中断关闭（例如关闭码 1006），且会话与租约仍有效
- **THEN** 页面在 30 秒内自动重连成功，原版界面状态保留，不显示错误提示

#### Scenario: Platform restart
- **WHEN** 平台进程重启
- **THEN** 已打开的页面自动重连并重新获取租约，无需操作者重新输入口令

### Requirement: No replay of unconfirmed operations
自动重连 MUST NOT 重放断开前已发出但未收到结果的请求；这些请求 SHALL 以“执行状态未知”失败，交由应用自身处理。

#### Scenario: Request in flight during disconnect
- **WHEN** 一个请求已发往实例但在收到响应前连接中断
- **THEN** 该请求以 `DEADLINE_EXCEEDED` / 状态未知失败，重连后不会再次发送

### Requirement: Clear notice when recovery is impossible
当连接无法自动恢复时，页面 SHALL 显示可见提示，说明原因类别与操作者可采取的动作，不得静默白屏。

#### Scenario: Session expired or revoked
- **WHEN** 重连因会话过期或被注销而被拒绝
- **THEN** 页面提示需要重新登录，并提供回到控制台的入口

#### Scenario: Instance taken over or unavailable
- **WHEN** 重连因租约被其他标签页持有，或实例 / 适配器不可用而被拒绝
- **THEN** 页面分别提示“已在其他标签页打开”或“实例当前不可用”及原因码，停止自动重试
