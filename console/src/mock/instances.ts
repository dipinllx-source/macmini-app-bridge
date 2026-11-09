// ⚠️ MOCK 数据：仅用于骨架阶段展示页面结构，不代表任何真实实例状态。
// 能力状态与设计文档 §2.3 的原型验收结论一致；平台实际尚未接管任何应用。

export type CapabilityState = 'SUPPORTED' | 'UNVERIFIED' | 'DISABLED' | 'UNSUPPORTED'
export type HealthLevel = 'L0_PROCESS' | 'L1_LISTENER' | 'L2_BRIDGE' | 'L3_RENDER' | 'L4_BUSINESS'
export type ActualState = 'OBSERVING' | 'READY' | 'DEGRADED' | 'BLOCKED' | 'WAIT_CONFIRM' | 'STOPPED_SUPPRESSED'

export interface MockInstance {
  id: string
  appId: string
  displayName: string
  strategy: string
  port: number
  desiredState: 'RUNNING' | 'STOPPED' | 'OBSERVE_ONLY'
  actualState: ActualState
  blockedReason?: string
  generation: number
  healthVerified: Partial<Record<HealthLevel, boolean>>
  lease?: { holder: string; expiresInSec: number }
  appVersion: string
  profileVersion: string
  risk: 'low' | 'medium' | 'high'
  capabilities: Record<string, CapabilityState>
}

export const MOCK_INSTANCES: MockInstance[] = [
  {
    id: 'qwen-01',
    appId: 'qwenwork',
    displayName: '千问',
    strategy: 'electron-main-inject',
    port: 8444,
    desiredState: 'OBSERVE_ONLY',
    actualState: 'WAIT_CONFIRM',
    blockedReason: '应用以普通方式启动，无法安全注入；需确认重启',
    generation: 3,
    healthVerified: { L0_PROCESS: true, L1_LISTENER: false },
    appVersion: 'mock-2.x',
    profileVersion: 'draft',
    risk: 'medium',
    capabilities: {
      'ui.render': 'SUPPORTED', 'ui.history': 'SUPPORTED', 'ui.compose': 'UNVERIFIED',
      'stream.push': 'UNVERIFIED', 'file.upload': 'UNVERIFIED', 'media.voice': 'UNVERIFIED', 'app.git': 'UNSUPPORTED',
    },
  },
  {
    id: 'codex-01',
    appId: 'codex',
    displayName: 'Codex',
    strategy: 'electron-renderer-cdp',
    port: 8445,
    desiredState: 'OBSERVE_ONLY',
    actualState: 'READY',
    generation: 1,
    healthVerified: { L0_PROCESS: true, L1_LISTENER: true, L2_BRIDGE: true, L3_RENDER: true },
    lease: { holder: 'operator (mock)', expiresInSec: 42 },
    appVersion: 'mock-26.x',
    profileVersion: 'draft',
    risk: 'high',
    capabilities: {
      'ui.render': 'SUPPORTED', 'ui.history': 'UNVERIFIED', 'ui.compose': 'UNVERIFIED',
      'stream.push': 'UNVERIFIED', 'file.upload': 'UNSUPPORTED', 'media.voice': 'UNVERIFIED', 'app.git': 'UNVERIFIED',
    },
  },
]
