<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { listAudit, listInstances, openInstance, type ApiError, type AuditRow, type LiveInstance } from '../api/bridge'
import { describeError, useSession } from '../composables/useSession'

const { expired } = useSession()
const instances = ref<LiveInstance[]>([])
const events = ref<AuditRow[]>([])
const loaded = ref(false)
const toast = ref<{ text: string; kind: 'ok' | 'err' } | null>(null)
const opening = ref<string | null>(null)
let timer: number | undefined

// ------------------------------------------------------------------ 每个应用的“图鉴资料”
const PROFILE: Record<string, { emoji: string; color: string; tagline: string }> = {
  qwenwork: { emoji: '🌀', color: '#8b7cf6', tagline: '千问办公 · 主进程注入' },
  codex: { emoji: '⚡', color: '#f5b400', tagline: 'Codex · 页面进程调试接口' },
  mock: { emoji: '🧪', color: '#9aa0a6', tagline: '离线测试用的小家伙' },
}
const TYPES: Record<string, { label: string; cls: string }> = {
  'electron-main-inject': { label: '注入系', cls: 'inject' },
  'electron-renderer-cdp': { label: '电气系', cls: 'cdp' },
  'test-mock': { label: '一般系', cls: 'mock' },
}
const SKILL_LABEL: Record<string, string> = { SUPPORTED: '已掌握', UNVERIFIED: '修炼中', DISABLED: '已封印', UNSUPPORTED: '未习得' }
const CAP_LABEL: Record<string, string> = {
  'ui.render': '原版界面', 'ui.history': '历史会话', 'ui.compose': '发消息', 'stream.push': '实时推送',
  'file.upload': '上传附件', 'file.drop': '拖放文件', 'clipboard.write': '复制', 'clipboard.read': '粘贴读取',
  'media.voice': '语音', 'app.git': 'Git', 'app.extension': '插件',
}

function profile(i: LiveInstance) {
  return PROFILE[i.appId] ?? { emoji: '❔', color: '#68a090', tagline: i.appId }
}
function type(i: LiveInstance) {
  return TYPES[i.strategy] ?? { label: '未知系', cls: 'unknown' }
}

/** 实态 → HP 条与心情。 */
function mood(i: LiveInstance) {
  switch (i.state) {
    case 'READY': return { hp: 100, cls: 'green', text: '精神饱满', pulse: false }
    case 'STARTING': return { hp: 60, cls: 'yellow', text: '正在苏醒…', pulse: true }
    case 'BLOCKED': return { hp: 30, cls: 'red', text: blockedText(i.blockedReason), pulse: false }
    case 'ADAPTER_DOWN': return { hp: 12, cls: 'red', text: '累倒了（适配器不可用）', pulse: true }
    default: return { hp: 0, cls: 'grey', text: '在休息（未启用）', pulse: false }
  }
}
function blockedText(reason: string | null) {
  if (reason === 'BLOCKED_UPGRADE') return '被封印：本地应用已升级，等待适配'
  if (reason === 'COMPATIBILITY_BLOCKED') return '被封印：版本不兼容'
  return `被封印：${reason ?? '原因未知'}`
}
function buttonText(i: LiveInstance) {
  if (opening.value === i.id) return '召唤中…'
  if (!i.enabled) return '休息中'
  if (!i.openable) return '暂时出不了门'
  if (i.lease.held && !i.lease.mine) return '在别处运行中'
  return i.lease.mine ? '再开一扇窗' : '出发！'
}
function buttonHint(i: LiveInstance) {
  if (i.lease.held && !i.lease.mine) return '同一时间只允许一个页面使用该应用；关闭另一个页面，或等它断开后会自动让位'
  if (!i.openable) return mood(i).text
  return '在新标签页打开原版界面'
}
function leaseText(i: LiveInstance) {
  if (i.lease.mine) return { text: '出战中（你）', cls: 'mine' }
  if (i.lease.held) return { text: '在别处出战', cls: 'busy' }
  return { text: '待命', cls: 'idle' }
}
const visibleCaps = (i: LiveInstance) =>
  Object.entries(i.capabilities ?? {}).filter(([, s]) => s !== 'UNSUPPORTED')

// ------------------------------------------------------------------ 徽章统计
const enabled = computed(() => instances.value.filter((i) => i.enabled))
const ready = computed(() => enabled.value.filter((i) => i.state === 'READY').length)
const inBattle = computed(() => instances.value.filter((i) => i.lease.held).length)
const recovered = computed(() => events.value.filter((e) => e.action === 'transport.resume').length)

// ------------------------------------------------------------------ 冒险日志
const NAME = computed(() => Object.fromEntries(instances.value.map((i) => [i.id, i.displayName])))
function who(id: string) {
  return NAME.value[id] ?? id
}
function storyOf(e: AuditRow): { icon: string; text: string } | null {
  const app = who(e.instance)
  switch (e.action) {
    case 'transport.open': return e.result === 'OK' ? { icon: '✨', text: `${app} 被召唤出场` } : { icon: '🚧', text: `${app} 已在别处出战，未能召唤` }
    case 'transport.resume': return { icon: '💚', text: `${app} 断线后满血归来（无损恢复）` }
    case 'transport.suspend': return { icon: '💤', text: `${app} 暂时离场，等待归来` }
    case 'transport.close':
      if (e.result === 'OK') return /RESUME_EXPIRED/.test(e.detail) ? { icon: '🌙', text: `${app} 等太久，先回去休息了` } : { icon: '👋', text: `${app} 收工离场` }
      return { icon: '⚠️', text: `${app} 被迫离场（${e.result}）` }
    case 'ticket.exchange': return e.result === 'OK' ? { icon: '🚪', text: `为 ${app} 打开了一扇新窗口` } : null
    case 'auth.login': return e.result === 'OK' ? { icon: '🎒', text: '训练家登录' } : { icon: '🔒', text: '有人尝试登录但口令不对' }
    case 'auth.logout': return { icon: '🏁', text: '训练家离开' }
    case 'auth.revoke-all': return e.result === 'OK' ? { icon: '🧹', text: '注销了所有会话' } : null
    case 'adapter.start': return { icon: '🔌', text: `${app} 的适配器准备就绪` }
    case 'instance.generation': return { icon: '🔄', text: `${app} 重新进化（适配器重启）` }
    default: return null
  }
}
const story = computed(() =>
  events.value.map((e) => ({ e, s: storyOf(e) })).filter((x) => x.s).slice(0, 12))
function when(at: string | number) {
  const d = new Date(at)
  const today = new Date().toDateString() === d.toDateString()
  return today ? d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    : d.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}

// ------------------------------------------------------------------ 数据
async function refresh() {
  try {
    const [list, audit] = await Promise.all([listInstances(), listAudit({ limit: 120 })])
    instances.value = list
    events.value = audit.events
    loaded.value = true
  } catch (e) {
    if ((e as ApiError).status === 401) expired()
    else flash(describeError(e), 'err')
  }
}
function flash(text: string, kind: 'ok' | 'err') {
  toast.value = { text, kind }
  setTimeout(() => (toast.value = null), 3500)
}
async function go(i: LiveInstance) {
  opening.value = i.id
  try {
    await openInstance(i.id)
    flash(`${i.displayName}，就决定是你了！已在新标签页打开`, 'ok')
    await refresh()
  } catch (e) {
    flash(describeError(e), 'err')
  } finally {
    opening.value = null
  }
}
function tick() {
  if (document.visibilityState === 'visible') refresh()
}
onMounted(() => {
  refresh()
  timer = window.setInterval(tick, 5000)
  document.addEventListener('visibilitychange', tick)
})
onUnmounted(() => {
  clearInterval(timer)
  document.removeEventListener('visibilitychange', tick)
})
</script>

<template>
  <div class="overview">
    <!-- 徽章栏 -->
    <div class="badges">
      <div class="badge dex-card pop-in">
        <div class="b-icon">🏅</div>
        <div><div class="b-num">{{ ready }}/{{ enabled.length }}</div><div class="b-label">健康应用（精神饱满）</div></div>
      </div>
      <div class="badge dex-card pop-in" style="animation-delay: 60ms">
        <div class="b-icon">⚔️</div>
        <div><div class="b-num">{{ inBattle }}</div><div class="b-label">运行中的页面（出战中）</div></div>
      </div>
      <div class="badge dex-card pop-in" style="animation-delay: 120ms">
        <div class="b-icon">💚</div>
        <div><div class="b-num">{{ recovered }} 次</div><div class="b-label">断线后无损恢复</div></div>
      </div>
    </div>

    <transition name="toast"><div v-if="toast" class="toast" :class="toast.kind">{{ toast.text }}</div></transition>

    <div class="grid">
      <!-- 应用卡 -->
      <div class="cards">
        <div v-if="!loaded" class="dex-card empty">正在翻阅图鉴…</div>
        <article v-for="(i, idx) in instances" :key="i.id" class="app-card dex-card pop-in"
          :class="{ off: !i.enabled }" :style="{ animationDelay: `${idx * 80}ms`, '--accent': profile(i).color }">
          <div class="card-head">
            <div class="avatar" :class="{ sleepy: i.state !== 'READY' }">
              <span>{{ profile(i).emoji }}</span>
            </div>
            <div class="title">
              <div class="name-row">
                <span class="name dex-title">{{ i.displayName }}</span>
                <span class="lv">Lv.{{ i.generation }}</span>
              </div>
              <div class="tagline">{{ profile(i).tagline }}</div>
              <div class="chips">
                <span class="type-badge" :class="type(i).cls">{{ type(i).label }}</span>
                <span class="lease" :class="leaseText(i).cls">{{ leaseText(i).text }}</span>
                <span class="port">:{{ i.port }}</span>
              </div>
            </div>
          </div>

          <div class="hp">
            <span class="hp-label">HP</span>
            <div class="hp-track"><div class="hp-fill" :class="[mood(i).cls, { pulse: mood(i).pulse }]" :style="{ width: mood(i).hp + '%' }" /></div>
          </div>
          <div class="mood">{{ mood(i).text }}</div>

          <div v-if="visibleCaps(i).length" class="skills">
            <span v-for="[cap, st] in visibleCaps(i)" :key="cap" class="skill" :class="st" :title="SKILL_LABEL[st]">
              {{ CAP_LABEL[cap] ?? cap }}<small> · {{ SKILL_LABEL[st] }}</small>
            </span>
          </div>

          <div class="actions">
            <button class="go-btn" :disabled="!i.openable || (i.lease.held && !i.lease.mine) || opening === i.id"
              :title="buttonHint(i)" @click="go(i)">
              {{ buttonText(i) }}
            </button>
            <router-link class="more" :to="`/instances/${i.id}`">详情 ›</router-link>
          </div>
        </article>
      </div>

      <!-- 冒险日志 -->
      <aside class="log dex-card pop-in" style="animation-delay: 160ms">
        <h3 class="dex-title">📜 冒险日志</h3>
        <ul v-if="story.length">
          <li v-for="x in story" :key="x.e.seq">
            <span class="ico">{{ x.s!.icon }}</span>
            <span class="txt">{{ x.s!.text }}</span>
            <time>{{ when(x.e.at) }}</time>
          </li>
        </ul>
        <p v-else class="quiet">还没有新的冒险……打开一个应用试试吧！</p>
        <router-link class="more" to="/security">查看完整审计 ›</router-link>
      </aside>
    </div>
  </div>
</template>

<style scoped>
.badges { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; margin-bottom: 18px; }
.badge { display: flex; align-items: center; gap: 12px; padding: 12px 16px; }
.b-icon { font-size: 28px; animation: floaty 3.2s ease-in-out infinite; }
.b-num { font-size: 24px; font-weight: 900; line-height: 1; }
.b-label { font-size: 12px; color: var(--dex-ink-soft); font-weight: 700; margin-top: 3px; }

.grid { display: grid; grid-template-columns: minmax(0, 1fr) 320px; gap: 18px; align-items: start; }
.cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; align-items: stretch; }
.empty { padding: 30px; text-align: center; color: var(--dex-ink-soft); font-weight: 700; }

.app-card {
  padding: 16px 18px 14px; position: relative; overflow: hidden; display: flex; flex-direction: column;
  transition: transform 0.18s cubic-bezier(.2,.9,.2,1.4), box-shadow 0.18s;
}
.app-card::before {
  content: ''; position: absolute; inset: 0 0 auto 0; height: 64px;
  background: linear-gradient(135deg, var(--accent) 0%, transparent 85%); opacity: 0.18; pointer-events: none;
}
.app-card:hover { transform: translateY(-4px) rotate(-0.4deg); box-shadow: 0 8px 0 rgba(43,45,66,0.9); }
.app-card.off { filter: saturate(0.3); opacity: 0.75; }
.card-head { display: flex; gap: 14px; align-items: center; position: relative; }
.avatar {
  width: 64px; height: 64px; flex: none; border-radius: 50%; display: grid; place-items: center;
  font-size: 34px; background: #fff; border: 3px solid var(--dex-line);
  box-shadow: inset 0 -6px 0 rgba(0,0,0,0.06), 0 0 0 4px var(--accent);
  animation: floaty 3s ease-in-out infinite;
}
.avatar.sleepy { animation-duration: 6s; filter: grayscale(0.4); }
.title { min-width: 0; }
.name-row { display: flex; align-items: baseline; gap: 8px; }
.name { font-size: 19px; }
.lv { font-size: 12px; font-weight: 900; color: var(--dex-ink-soft); }
.tagline { font-size: 12px; color: var(--dex-ink-soft); margin: 2px 0 6px; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
.lease { font-size: 12px; font-weight: 800; padding: 1px 8px; border-radius: 999px; border: 2px solid var(--dex-line); background: #fff; }
.lease.mine { background: var(--dex-yellow); }
.lease.busy { background: #ffe1d9; }
.port { font-size: 12px; font-family: ui-monospace, Menlo, monospace; color: var(--dex-ink-soft); }
.hp { margin-top: 14px; }
.mood { font-size: 13px; font-weight: 700; margin: 6px 0 10px; }
.skills { margin-bottom: 4px; }
.skill small { font-weight: 600; opacity: 0.75; }
.actions { display: flex; align-items: center; justify-content: space-between; margin-top: auto; padding-top: 12px; }
.more { font-size: 13px; font-weight: 800; color: var(--dex-red); text-decoration: none; }
.more:hover { text-decoration: underline; }

.log { padding: 16px 16px 12px; position: sticky; top: 14px; }
.log h3 { margin: 0 0 10px; font-size: 16px; }
.log ul { list-style: none; margin: 0 0 8px; padding: 0; }
.log li { display: grid; grid-template-columns: 22px 1fr 44px; gap: 6px; align-items: baseline; padding: 7px 0; border-bottom: 2px dashed #eceaf0; font-size: 13px; }
.log li:last-child { border-bottom: none; }
.log time { text-align: right; font-size: 11px; color: var(--dex-ink-soft); font-variant-numeric: tabular-nums; }
.quiet { color: var(--dex-ink-soft); font-size: 13px; }

.toast {
  position: fixed; left: 50%; bottom: 28px; transform: translateX(-50%); z-index: 20;
  padding: 10px 18px; border-radius: 14px; border: 3px solid var(--dex-line); box-shadow: var(--dex-shadow);
  font-weight: 800; background: #fff;
}
.toast.ok { background: #e3fbe7; }
.toast.err { background: #ffe1d9; }
.toast-enter-active, .toast-leave-active { transition: all 0.25s; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translate(-50%, 16px); }

@media (max-width: 1100px) { .cards { grid-template-columns: 1fr; } }
@media (max-width: 900px) {
  .grid { grid-template-columns: 1fr; }
  .log { position: static; }
  .badges { grid-template-columns: 1fr; }
}
</style>
