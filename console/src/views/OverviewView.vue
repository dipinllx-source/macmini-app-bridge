<script setup lang="ts">
import DexIcon from '../components/DexIcon.vue'
import PreflightDialog from '../components/PreflightDialog.vue'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { listAudit, listInstances, openInstance, preflightInstance, type ApiError, type AuditRow, type LiveInstance, type PreflightResult } from '../api/bridge'
import { describeError, useSession } from '../composables/useSession'
import { CAP_LABEL, SKILL_LABEL, iconUrl, leaseText, mood, profile, typeOf as type } from '../dex'

const { expired } = useSession()
const instances = ref<LiveInstance[]>([])
const events = ref<AuditRow[]>([])
const loaded = ref(false)
const toast = ref<{ text: string; kind: 'ok' | 'err' } | null>(null)
const opening = ref<string | null>(null)
const iconFailed = ref<Record<string, boolean>>({})
let timer: number | undefined

// 图鉴资料（外观、类型徽章、能力文案）见 ../dex.ts
function buttonText(i: LiveInstance) {
  if (opening.value === i.id) return '召唤中…'
  if (!i.enabled) return '休息中'
  if (!i.openable) return i.preflight ? '检查并恢复' : '暂时出不了门'
  if (i.lease.held && !i.lease.mine) return '在别处运行中'
  return i.lease.mine ? '再开一扇窗' : '出发！'
}
function buttonHint(i: LiveInstance) {
  if (i.lease.held && !i.lease.mine) return '同一时间只允许一个页面使用该应用；关闭另一个页面，或等它断开后会自动让位'
  if (!i.openable) return mood(i).text
  return '在新标签页打开原版界面'
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
    case 'transport.open': return e.result === 'OK' ? { icon: 'spark', text: `${app} 被召唤出场` } : { icon: 'alert', text: `${app} 已在别处出战，未能召唤` }
    case 'transport.resume': return { icon: 'revive', text: `${app} 断线后满血归来（无损恢复）` }
    case 'transport.suspend': return { icon: 'pause', text: `${app} 暂时离场，等待归来` }
    case 'transport.close':
      if (e.result === 'OK') return /RESUME_EXPIRED/.test(e.detail) ? { icon: 'moon', text: `${app} 等太久，先回去休息了` } : { icon: 'exit', text: `${app} 收工离场` }
      return { icon: 'alert', text: `${app} 被迫离场（${e.result}）` }
    case 'ticket.exchange': return e.result === 'OK' ? { icon: 'door', text: `为 ${app} 打开了一扇新窗口` } : null
    case 'auth.login': return e.result === 'OK' ? { icon: 'key', text: '训练家登录' } : { icon: 'lock', text: '有人尝试登录但口令不对' }
    case 'auth.logout': return { icon: 'flag', text: '训练家离开' }
    case 'auth.revoke-all': return e.result === 'OK' ? { icon: 'broom', text: '注销了所有会话' } : null
    case 'adapter.start': return { icon: 'plug', text: `${app} 的适配器准备就绪` }
    case 'instance.generation': return { icon: 'evolve', text: `${app} 重新进化（适配器重启）` }
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
// 打开前检查（配置了 preflight 的实例，例如 Codex 自动升级后）：不符合预期时弹出说明与“恢复”
const pf = ref<{ inst: LiveInstance; result: PreflightResult } | null>(null)
async function openNow(i: LiveInstance) {
  opening.value = i.id
  try {
    await openInstance(i.id)
    flash(`${i.displayName}，就决定是你了！已在新标签页打开`, 'ok')
    await refresh()
  } catch (e) {
    if ((e as ApiError).status === 401) return expired()
    flash(describeError(e), 'err')
  } finally {
    opening.value = null
  }
}
async function go(i: LiveInstance) {
  if (i.preflight) {
    opening.value = i.id
    try {
      const r = await preflightInstance(i.id)
      if (!r.ok) {
        pf.value = { inst: i, result: r }
        return
      }
    } catch (e) {
      if ((e as ApiError).status === 401) return expired()
      flash(`检查失败：${describeError(e)}`, 'err')
      return
    } finally {
      opening.value = null
    }
    if (!i.openable) return flash(mood(i).text, 'err')
  }
  await openNow(i)
}
function pfOpen() {
  const i = pf.value?.inst
  pf.value = null
  if (i) openNow(i)
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
        <div class="b-icon medal"><DexIcon name="medal" :size="30" /></div>
        <div><div class="b-num">{{ ready }}/{{ enabled.length }}</div><div class="b-label">健康应用（精神饱满）</div></div>
      </div>
      <div class="badge dex-card pop-in" style="animation-delay: 60ms">
        <div class="b-icon play"><DexIcon name="play" :size="30" /></div>
        <div><div class="b-num">{{ inBattle }}</div><div class="b-label">运行中的页面（出战中）</div></div>
      </div>
      <div class="badge dex-card pop-in" style="animation-delay: 120ms">
        <div class="b-icon heart"><DexIcon name="heart" :size="30" /></div>
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
              <img v-if="!iconFailed[i.id]" :src="iconUrl(i.id)"
                :alt="i.displayName" draggable="false" @error="iconFailed[i.id] = true" />
              <DexIcon v-else class="fallback-icon" :name="profile(i).fallback" :size="48" />
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
            <button class="go-btn" :disabled="(!i.openable && !i.preflight) || (i.lease.held && !i.lease.mine) || opening === i.id"
              :title="buttonHint(i)" @click="go(i)">
              {{ buttonText(i) }}
            </button>
            <router-link class="more" :to="`/instances/${i.id}`">详情 ›</router-link>
          </div>
        </article>
      </div>

      <!-- 冒险日志 -->
      <aside class="log dex-card pop-in" style="animation-delay: 160ms">
        <h3 class="dex-title"><DexIcon name="scroll" :size="20" /> 冒险日志</h3>
        <ul v-if="story.length">
          <li v-for="x in story" :key="x.e.seq">
            <span class="ico" :class="x.s!.icon"><DexIcon :name="x.s!.icon" :size="19" /></span>
            <span class="txt">{{ x.s!.text }}</span>
            <time>{{ when(x.e.at) }}</time>
          </li>
        </ul>
        <p v-else class="quiet">还没有新的冒险……打开一个应用试试吧！</p>
        <router-link class="more" to="/security">查看完整审计 ›</router-link>
      </aside>
    </div>
    <PreflightDialog v-if="pf" :inst="pf.inst" :result="pf.result" @close="pf = null" @open="pfOpen" @recovered="refresh" @expired="expired" />
  </div>
</template>
