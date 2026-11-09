# Spec Delta

## Purpose

定义操作者会话的期限、续期、持久化与撤销规则，使个人长期使用的远程入口在可配置的期限内免重复登录，同时保留随时注销的能力。

## ADDED Requirements

### Requirement: Configurable session lifetime
控制台会话的最长期限 SHALL 可配置，默认 7 天；超过最长期限后会话 MUST 失效，操作者需重新输入口令。

#### Scenario: Default lifetime
- **WHEN** 未配置会话期限，操作者登录后持续使用
- **THEN** 会话在登录 7 天后失效，之后访问控制台或实例页面要求重新登录

#### Scenario: Custom lifetime
- **WHEN** 配置 `mmab.timeouts.session-max-age` 为 30 天
- **THEN** 会话最长有效 30 天

### Requirement: Idle expiry is configurable and defaults to the full lifetime
会话闲置过期 SHALL 可配置，默认等于最长期限，即期限内只要有过使用就不会因闲置失效。

#### Scenario: Unused for a day
- **WHEN** 使用默认配置，操作者登录后 24 小时未访问
- **THEN** 会话仍有效，再次打开页面无需登录

### Requirement: Instance activity keeps the console session alive
在实例页面上的活动（包括数据连接心跳）SHALL 视为其父控制台会话的活动，不得出现实例页面仍在使用、控制台会话却因闲置失效并连带撤销实例会话的情况。

#### Scenario: Only the instance tab is used
- **WHEN** 操作者只使用实例页面，控制台页面已关闭超过原闲置期限
- **THEN** 控制台会话与实例会话都保持有效

### Requirement: Sessions survive platform and browser restarts
会话 SHALL 在平台重启与浏览器重启后保持有效（直到期限或被注销）；平台 MUST 只保存会话令牌的散列，不保存明文令牌、口令或 Cookie 值。

#### Scenario: Platform restarted
- **WHEN** 平台进程或电脑重启后，操作者重新打开控制台
- **THEN** 无需重新输入口令

#### Scenario: Browser restarted
- **WHEN** 操作者关闭并重新打开浏览器
- **THEN** 会话 Cookie 仍在，无需重新登录

### Requirement: Leases and launch tickets do not survive restarts
平台重启后 MUST 作废所有租约与开窗票据；浏览器 SHALL 以仍有效的会话重新获取租约。

#### Scenario: Restart with an open page
- **WHEN** 平台重启时有实例页面处于打开状态
- **THEN** 旧租约不可再用；页面重连时获得新的租约与 fencing token

### Requirement: Operator can revoke all sessions
操作者 SHALL 能在控制台一次注销全部会话；注销后所有已打开的控制台与实例页面 MUST 立即失去访问。

#### Scenario: Revoke everywhere
- **WHEN** 操作者在控制台执行“注销所有会话”
- **THEN** 所有会话被撤销，打开中的数据连接被关闭，其他设备上的页面提示需要重新登录
