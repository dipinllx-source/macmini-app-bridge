// 控制台共用的“图鉴资料”：应用外观、桥接类型徽章、能力与状态文案（总览与详情页共用）。
import type { LiveInstance } from './api/bridge'

export const PROFILE: Record<string, { fallback: string; color: string; tagline: string }> = {
  qwenwork: { fallback: 'app', color: '#8b7cf6', tagline: '主进程注入 · 原版界面' },
  codex: { fallback: 'app', color: '#f5b400', tagline: '页面进程调试接口 · 原版界面' },
  mock: { fallback: 'flask', color: '#9aa0a6', tagline: '离线测试用的小家伙' },
}
export const TYPES: Record<string, { label: string; cls: string; hint: string }> = {
  'electron-main-inject': { label: '注入系', cls: 'inject', hint: '把桥注入应用主进程' },
  'electron-renderer-cdp': { label: '电气系', cls: 'cdp', hint: '经页面进程调试接口接入' },
  'test-mock': { label: '一般系', cls: 'mock', hint: '离线测试' },
}
export const SKILL_LABEL: Record<string, string> = {
  SUPPORTED: '已掌握', UNVERIFIED: '修炼中', DISABLED: '已封印', UNSUPPORTED: '未习得',
}
export const SKILL_HINT: Record<string, string> = {
  SUPPORTED: '已验证可用', UNVERIFIED: '尚未验证', DISABLED: '已关闭', UNSUPPORTED: '不支持',
}
export const CAP_LABEL: Record<string, string> = {
  'ui.render': '原版界面', 'ui.history': '历史会话', 'ui.compose': '发消息', 'stream.push': '实时推送',
  'file.upload': '上传附件', 'file.drop': '拖放文件', 'clipboard.write': '复制', 'clipboard.read': '粘贴读取',
  'media.voice': '语音', 'app.git': 'Git', 'app.extension': '插件',
}
export const STATE_LABEL: Record<string, string> = {
  READY: '可用', STARTING: '启动中', BLOCKED: '被阻塞', ADAPTER_DOWN: '适配器不可用', DISABLED: '未启用',
}
export const RISK_LABEL: Record<string, string> = { low: '低', medium: '中', high: '高' }

export function profile(i: LiveInstance) {
  return PROFILE[i.appId] ?? { fallback: 'app', color: '#68a090', tagline: i.appId }
}
export function typeOf(i: LiveInstance) {
  return TYPES[i.strategy] ?? { label: '未知系', cls: 'unknown', hint: i.strategy }
}
export function blockedText(reason: string | null) {
  if (reason === 'BLOCKED_UPGRADE') return '被封印：本地应用已升级，等待适配'
  if (reason === 'COMPATIBILITY_BLOCKED') return '被封印：版本不兼容'
  return `被封印：${reason ?? '原因未知'}`
}
/** 实态 → HP 条与心情。 */
export function mood(i: LiveInstance) {
  switch (i.state) {
    case 'READY': return { hp: 100, cls: 'green', text: '精神饱满', pulse: false }
    case 'STARTING': return { hp: 60, cls: 'yellow', text: '正在苏醒…', pulse: true }
    case 'BLOCKED': return { hp: 30, cls: 'red', text: blockedText(i.blockedReason), pulse: false }
    case 'ADAPTER_DOWN': return { hp: 12, cls: 'red', text: '累倒了（适配器不可用）', pulse: true }
    default: return { hp: 0, cls: 'grey', text: '在休息（未启用）', pulse: false }
  }
}
export function leaseText(i: LiveInstance) {
  if (i.lease.mine) return { text: '出战中（你）', cls: 'mine' }
  if (i.lease.held) return { text: '在别处出战', cls: 'busy' }
  return { text: '待命', cls: 'idle' }
}
export const iconUrl = (id: string) => `/__mmab/api/v1/instances/${encodeURIComponent(id)}/icon`
