# Spec Delta

## Purpose

让操作者在控制台一眼看清每个应用实例是否可用、不可用时卡在哪一层以及原因，尤其是本地应用升级导致的不兼容，而不必查看日志。

## ADDED Requirements

### Requirement: Console shows real instance state
控制台 SHALL 展示每个已登记实例的真实状态（不是示例数据）：期望状态、实际状态、当前是否被占用、应用版本、适配器版本与最近一次状态更新时间。

#### Scenario: Overview
- **WHEN** 操作者打开控制台总览
- **THEN** 每个实例卡片显示上述字段，数据来自平台实时观测，页面不含 mock 标记

### Requirement: Layered health is visible
控制台 SHALL 按 L0 进程、L1 监听、L2 桥接、L3 渲染、L4 业务分层显示健康状态，每层标明“正常 / 异常 / 未验证”及最近验证时间；未验证的层 MUST NOT 显示为正常。

#### Scenario: Bridge lost
- **WHEN** 应用进程在运行但桥接已断开
- **THEN** L0 显示正常，L2 显示异常及原因，L3/L4 不显示为正常

### Requirement: Upgrade incompatibility is surfaced, not auto-adapted
当本地应用版本与已提取资源或已批准的适配器版本不一致时，平台 SHALL 阻止该实例的新会话，并在控制台显示“版本不符 / 升级受阻”、已安装版本与期望版本；平台 MUST NOT 自动提取资源或自动放行新版本。

#### Scenario: Codex upgraded
- **WHEN** Codex 自动升级到新版本
- **THEN** codex-01 显示“升级受阻”，列出已安装版本与资源版本，打开该实例被拒绝并给出同样的原因

### Requirement: Actionable abnormal states
控制台 SHALL 对“需确认”“端口归属异常”“适配器不可用”“恢复失败”等异常显示原因说明；对需要操作者决定的状态提供对应操作入口。

#### Scenario: Needs confirmation
- **WHEN** qwen-01 处于“需确认”状态
- **THEN** 控制台显示原因与“重启并接入”按钮

### Requirement: Status updates without reload
控制台 SHALL 在实例状态变化后 10 秒内自动更新显示，无需操作者刷新页面。

#### Scenario: Instance recovers
- **WHEN** 某实例从“不可用”恢复为“可用”
- **THEN** 控制台在 10 秒内更新为“可用”
