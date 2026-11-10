<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import DexIcon from '../components/DexIcon.vue'
import { listAudit, listInstances, openInstance, type ApiError, type AuditRow, type LiveInstance } from '../api/bridge'
import { describeError, useSession } from '../composables/useSession'
import { CAP_LABEL, RISK_LABEL, SKILL_HINT, SKILL_LABEL, STATE_LABEL, iconUrl, leaseText, mood, profile, typeOf } from '../dex'

const props = defineProps<{ id: string }>()
const { expired } = useSession()
const inst = ref<LiveInstance | null>(null)
const events = ref<AuditRow[]>([])
const state = ref<'loading' | 'ok' | 'missing' | 'error'>('loading')
const msg = ref('')
const iconFailed = ref(false)
const opening = ref(false)
const open = ref(new Set<number>())
function toggle(seq: number) {
  const s = new Set(open.value)
  if (s.has(seq)) s.delete(seq)
  else s.add(seq)
  open.value = s
}

const caps = computed(() => Object.entries(inst.value?.capabilities ?? {}))

async function load() {
  state.value = 'loading'
  msg.value = ''
  try {
    const [list, audit] = await Promise.all([listInstances(), listAudit({ limit: 30, instance: props.id })])
    inst.value = list.find((i) => i.id === props.id) ?? null
    events.value = audit.events
    state.value = inst.value ? 'ok' : 'missing'
  } catch (e) {
    if ((e as ApiError).status === 401) {
      expired()
      return
    }
    msg.value = describeError(e)
    state.value = 'error'
  }
}

async function go() {
  if (!inst.value) return
  opening.value = true
  try {
    await openInstance(inst.value.id)
    await load()
  } catch (e) {
    msg.value = describeError(e)
  } finally {
    opening.value = false
  }
}

const fmt = (at: string | number) => new Date(at).toLocaleString('zh-CN', { hour12: false })
onMounted(load)
watch(() => props.id, load)
</script>

<template>
  <div class="detail">
    <router-link to="/" class="back"><span aria-hidden="true">‹</span> 返回应用总览</router-link>

    <div v-if="state === 'loading'" class="dex-card box muted">正在翻阅图鉴…</div>
    <div v-else-if="state === 'missing'" class="dex-card box">
      <p>没有找到实例 <code>{{ id }}</code>。它可能已从配置中移除。</p>
    </div>
    <div v-else-if="state === 'error'" class="dex-card box">
      <p class="err">加载失败：{{ msg }}</p>
      <button class="go-btn ghost small" @click="load">重试</button>
    </div>

    <template v-else-if="inst">
      <section class="dex-card hero" :style="{ '--accent': profile(inst).color }">
        <div class="avatar">
          <img v-if="!iconFailed" :src="iconUrl(inst.id)" :alt="inst.displayName" @error="iconFailed = true" />
          <DexIcon v-else :name="profile(inst).fallback" :size="48" />
        </div>
        <div class="hero-main">
          <div class="name-row">
            <h2 class="dex-title">{{ inst.displayName }}</h2>
            <span class="lv">Lv.{{ inst.generation }}</span>
            <span class="type-badge" :class="typeOf(inst).cls" :title="typeOf(inst).hint">{{ typeOf(inst).label }}</span>
          </div>
          <div class="tagline">{{ profile(inst).tagline }}</div>
          <div class="hp">
            <span class="hp-label">HP</span>
            <div class="hp-track"><div class="hp-fill" :class="[mood(inst).cls, { pulse: mood(inst).pulse }]" :style="{ width: mood(inst).hp + '%' }" /></div>
          </div>
          <div class="mood">{{ mood(inst).text }}</div>
        </div>
        <div class="hero-act">
          <p v-if="inst.lease.held && !inst.lease.mine" class="busy-hint">正被另一个页面或设备使用。关闭那个页面后即可在这里打开；断线挂起的页面会自动让位。</p>
          <button class="go-btn" :disabled="!inst.openable || (inst.lease.held && !inst.lease.mine) || opening" @click="go">
            {{ opening ? '召唤中…' : inst.lease.held && !inst.lease.mine ? '在别处运行中' : '出发！' }}
          </button>
        </div>
      </section>

      <div class="cols">
        <section class="dex-card box">
          <h3 class="dex-title">基本资料</h3>
          <dl>
            <dt>实例</dt><dd><code>{{ inst.id }}</code></dd>
            <dt>状态</dt><dd>{{ STATE_LABEL[inst.state] ?? inst.state }}<span v-if="inst.blockedReason" class="reason"> · {{ inst.blockedReason }}</span></dd>
            <dt>访问端口</dt><dd>{{ inst.port }}</dd>
            <dt>占用</dt><dd><span class="lease" :class="leaseText(inst).cls">{{ leaseText(inst).text }}</span></dd>
            <dt>桥接方式</dt><dd>{{ typeOf(inst).hint }}</dd>
            <dt>适配器</dt><dd><code>{{ inst.profileId ?? '-' }}</code></dd>
            <dt>风险等级</dt><dd>{{ inst.risk ? RISK_LABEL[inst.risk] ?? inst.risk : '-' }}</dd>
          </dl>
          <p class="note">HP 依据适配器与桥接是否在线；进程、监听、页面渲染的分层检查后续加入。</p>
        </section>

        <section class="dex-card box">
          <h3 class="dex-title">技能</h3>
          <div v-if="caps.length">
            <span v-for="[c, s] in caps" :key="c" class="skill" :class="s" :title="SKILL_HINT[s]">
              {{ CAP_LABEL[c] ?? c }}<small> · {{ SKILL_LABEL[s] ?? s }}</small>
            </span>
          </div>
          <p v-else class="muted">适配器未加载，暂无技能信息。</p>
        </section>
      </div>

      <section class="dex-card box">
        <h3 class="dex-title"><DexIcon name="scroll" :size="18" /> 最近记录</h3>
        <table v-if="events.length">
          <thead><tr><th>时间</th><th>动作</th><th>结果</th><th>细节（点击展开）</th></tr></thead>
          <tbody>
            <tr v-for="e in events" :key="e.seq" :class="{ expanded: open.has(e.seq) }" @click="toggle(e.seq)">
              <td class="t">{{ fmt(e.at) }}</td>
              <td><code>{{ e.action }}</code></td>
              <td><span class="res" :class="e.result === 'OK' ? 'good' : 'bad'">{{ e.result }}</span></td>
              <td class="d" :title="e.detail">{{ e.detail }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="muted">还没有记录。</p>
        <router-link class="more" :to="{ path: '/security' }">在“安全与审计”中查看全部 ›</router-link>
      </section>
    </template>
  </div>
</template>

<style scoped>
.detail { display: grid; gap: 16px; }
.back {
  justify-self: start; display: inline-flex; align-items: center; gap: 6px; text-decoration: none;
  font-weight: 800; font-size: 14px; color: var(--dex-ink); padding: 5px 14px 5px 10px; border-radius: 999px;
  background: #fff; border: 3px solid var(--dex-line); box-shadow: 0 3px 0 var(--dex-line); transition: transform 0.12s;
}
.back span { font-size: 20px; line-height: 1; margin-top: -2px; }
.back:hover { transform: translateX(-3px); }
.box { padding: 14px 18px; }
.muted { color: var(--dex-ink-soft); }
.err { color: var(--dex-red); font-weight: 700; }
.small { padding: 4px 12px; font-size: 12px; }

.hero { display: flex; gap: 18px; align-items: center; padding: 18px 20px; position: relative; overflow: hidden; }
.hero::before { content: ''; position: absolute; inset: 0; background: linear-gradient(110deg, var(--accent) 0%, transparent 60%); opacity: 0.16; pointer-events: none; }
.avatar {
  width: 88px; height: 88px; flex: none; border-radius: 26px; display: grid; place-items: center; background: #fff;
  border: 3px solid var(--dex-line); box-shadow: 0 0 0 5px var(--accent); overflow: hidden; color: var(--dex-ink);
  animation: floaty 3s ease-in-out infinite;
}
.avatar img { width: 84px; height: 84px; object-fit: contain; }
.hero-main { flex: 1; min-width: 0; }
.name-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.name-row h2 { margin: 0; font-size: 24px; }
.lv { font-weight: 900; color: var(--dex-ink-soft); }
.tagline { font-size: 13px; color: var(--dex-ink-soft); margin: 4px 0 10px; }
.mood { font-size: 13px; font-weight: 700; margin-top: 6px; }
.hero-act { flex: none; max-width: 230px; text-align: right; }
.busy-hint { font-size: 12px; color: var(--dex-ink-soft); margin: 0 0 8px; line-height: 1.5; }
th { text-align: left; padding: 6px; border-bottom: 3px solid var(--dex-line); font-size: 12px; white-space: nowrap; }
tbody tr { cursor: pointer; }
tbody tr:hover { background: #fff8e5; }
tr.expanded .d { white-space: normal; word-break: break-all; overflow: visible; }

.cols { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; }
h3 { margin: 0 0 10px; display: flex; align-items: center; gap: 6px; }
dl { display: grid; grid-template-columns: 96px 1fr; gap: 8px 12px; margin: 0; font-size: 14px; }
dt { color: var(--dex-ink-soft); font-weight: 700; }
dd { margin: 0; font-weight: 700; }
.reason { color: var(--dex-red); }
.lease { font-size: 12px; font-weight: 800; padding: 1px 8px; border-radius: 999px; border: 2px solid var(--dex-line); background: #fff; }
.lease.mine { background: var(--dex-yellow); }
.lease.busy { background: #ffe1d9; }
.note { font-size: 12px; color: var(--dex-ink-soft); margin: 12px 0 0; }
.skill small { font-weight: 600; opacity: 0.75; }

table { width: 100%; border-collapse: collapse; font-size: 13px; }
td { padding: 6px; border-bottom: 2px dashed #eceaf0; vertical-align: top; }
.t { white-space: nowrap; color: var(--dex-ink-soft); font-variant-numeric: tabular-nums; }
.d { max-width: 380px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
.res { font-weight: 800; padding: 0 8px; border-radius: 999px; border: 2px solid var(--dex-line); font-size: 12px; }
.res.good { background: #e3fbe7; }
.res.bad { background: #ffe1d9; }
.more { display: inline-block; margin-top: 10px; font-size: 13px; font-weight: 800; color: var(--dex-red); text-decoration: none; }

@media (max-width: 900px) {
  .cols { grid-template-columns: 1fr; }
  .hero { flex-wrap: wrap; }
}
</style>
