# Spec Delta

## Purpose

让平台在电脑重启或应用退出后，按每个实例的期望状态自动恢复可用，并在无法安全自动恢复时停下来等待操作者确认，而不是擅自打断操作者的应用。

## ADDED Requirements

### Requirement: Platform starts at login
平台 SHALL 在操作者登录 macOS 后自动启动，并在进程异常退出后被重新拉起；操作者显式停止平台后 MUST NOT 被自动拉起，直到再次显式启动。

#### Scenario: After reboot
- **WHEN** 电脑重启且操作者登录
- **THEN** 平台在 2 分钟内开始监听控制台与已启用实例的端口

#### Scenario: Explicit stop
- **WHEN** 操作者执行平台的停止命令
- **THEN** 平台退出且不会被自动重新启动

### Requirement: Dedicated Codex instance is brought up automatically
对启用的 Codex 实例，平台 SHALL 在其专用实例未运行时自动启动它，并且只附加到核验通过的专用实例；平台 MUST NOT 启动、附加或停止操作者自己的 Codex。

#### Scenario: After reboot
- **WHEN** 平台启动时 Codex 专用实例未运行
- **THEN** 平台启动专用实例并在其就绪后接入，控制台显示 codex-01 可用

#### Scenario: Port owned by something else
- **WHEN** 专用实例的调试端口被其他进程占用
- **THEN** 平台不附加、不终止占用者，控制台显示“端口归属异常”

### Requirement: QwenWork is started by the platform and injected
对启用的千问实例，平台 SHALL 在千问未运行时以平台所需的调试参数启动千问并完成接入，接入完成后 MUST 关闭临时调试端口。

#### Scenario: After reboot
- **WHEN** 平台启动时千问未运行
- **THEN** 平台启动千问、完成注入、关闭调试端口，控制台显示 qwen-01 可用

#### Scenario: QwenWork restarted with the platform-required arguments
- **WHEN** 千问以平台所需的参数运行但桥接已丢失（例如千问被重新启动过）
- **THEN** 平台在已证明安全的前提下重新接入，否则显示“需确认”

### Requirement: No forced restart of an operator-started app
当应用以平台无法安全接入的方式运行时（例如千问被普通方式打开），平台 MUST NOT 自动退出、重启或向其发送信号；SHALL 在控制台显示“需确认”及原因，并提供由操作者触发的“重启并接入”。

#### Scenario: QwenWork opened from the Dock
- **WHEN** 千问以普通方式运行
- **THEN** 控制台显示 qwen-01 “需确认：应用未以可接入方式启动”，不发生任何自动重启

#### Scenario: Operator confirms
- **WHEN** 操作者在控制台点击“重启并接入”
- **THEN** 平台请求千问正常退出（需要时等待操作者在 Mac 上确认退出），随后以可接入方式启动并完成接入

### Requirement: Recovery is bounded
自动恢复 SHALL 带有退避与次数上限；连续失败后 MUST 停止重试并在控制台显示失败原因，直到操作者处理或环境变化。

#### Scenario: Repeated launch failure
- **WHEN** 同一实例连续 3 次自动拉起或接入失败
- **THEN** 平台停止自动重试，控制台显示该实例“恢复失败”及最近一次原因
