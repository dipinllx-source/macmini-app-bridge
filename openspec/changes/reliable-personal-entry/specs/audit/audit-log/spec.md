# Spec Delta

## Purpose

保存平台安全与运行相关事件的元数据记录，供操作者事后排查断线、登录与恢复问题，同时保证不记录任何业务内容与凭据。

## ADDED Requirements

### Requirement: Audit records persist across restarts
审计记录 SHALL 持久保存，平台或电脑重启后仍可在控制台查看。

#### Scenario: After restart
- **WHEN** 平台重启后操作者打开“安全与审计”
- **THEN** 能看到重启前的审计记录

### Requirement: Audit records metadata only
审计记录 MUST 只包含时间、操作者会话标识、动作、实例、结果码与固定格式的诊断元数据（如大小、计数、关闭码），MUST NOT 包含消息内容、口令、Cookie、票据或令牌。

#### Scenario: Disconnect diagnosis
- **WHEN** 数据连接因缓冲超限被关闭
- **THEN** 审计记录包含原因、帧大小与排队计数，不包含任何负载内容

### Requirement: Bounded retention
审计记录 SHALL 有可配置的保留期（默认 30 天），超期记录自动清除。

#### Scenario: Old records
- **WHEN** 审计记录超过保留期
- **THEN** 该记录不再出现在控制台，也不再占用存储
