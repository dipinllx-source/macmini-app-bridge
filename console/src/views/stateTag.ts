import type { CapabilityState } from '../mock/instances'

export function capabilityTagType(s: CapabilityState): 'success' | 'info' | 'warning' | 'danger' {
  return s === 'SUPPORTED' ? 'success' : s === 'UNVERIFIED' ? 'warning' : s === 'DISABLED' ? 'info' : 'danger'
}

export const CAPABILITY_LABEL: Record<CapabilityState, string> = {
  SUPPORTED: '已支持',
  UNVERIFIED: '未验证',
  DISABLED: '已关闭',
  UNSUPPORTED: '不支持',
}
