<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { listAudit, listInstances, type ApiError, type AuditRow, type LiveInstance } from '../api/bridge'
import { describeError, useSession } from '../composables/useSession'

const props = defineProps<{ id: string }>()
const { expired } = useSession()
const inst = ref<LiveInstance | null>(null)
const events = ref<AuditRow[]>([])
const msg = ref('')
const SKILL_LABEL: Record<string, string> = { SUPPORTED: '已掌握', UNVERIFIED: '修炼中', DISABLED: '已封印', UNSUPPORTED: '未习得' }
const caps = computed(() => Object.entries(inst.value?.capabilities ?? {}))

onMounted(async () => {
  try {
    const [list, audit] = await Promise.all([listInstances(), listAudit({ limit: 30, instance: props.id })])
    inst.value = list.find((i) => i.id === props.id) ?? null
    events.value = audit.events
  } catch (e) {
    if ((e as ApiError).status === 401) expired()
    else msg.value = describeError(e)
  }
})
</script>

<template>
  <p v-if="msg" class="err">{{ msg }}</p>
  <div v-if="inst" class="detail">
    <div class="dex-card box">
      <h3 class="dex-title">{{ inst.displayName }} <small>（{{ inst.id }}）</small></h3>
      <dl>
        <dt>实际状态</dt><dd>{{ inst.state }}<span v-if="inst.blockedReason"> · {{ inst.blockedReason }}</span></dd>
        <dt>端口</dt><dd>{{ inst.port }}</dd>
        <dt>代际</dt><dd>Lv.{{ inst.generation }}</dd>
        <dt>适配器 profile</dt><dd>{{ inst.profileId ?? '-' }}</dd>
        <dt>风险等级</dt><dd>{{ inst.risk ?? '-' }}</dd>
        <dt>占用</dt><dd>{{ inst.lease.mine ? '出战中（你）' : inst.lease.held ? '在别处出战' : '待命' }}</dd>
      </dl>
      <p class="note">分层健康（进程 / 监听 / 桥接 / 渲染 / 业务）将在“控制台真实状态”任务中补齐。</p>
    </div>
    <div class="dex-card box">
      <h3 class="dex-title">技能</h3>
      <span v-for="[c, s] in caps" :key="c" class="skill" :class="s">{{ c }} · {{ SKILL_LABEL[s] }}</span>
    </div>
    <div class="dex-card box">
      <h3 class="dex-title">最近记录</h3>
      <ul>
        <li v-for="e in events" :key="e.seq"><time>{{ new Date(e.at).toLocaleString('zh-CN', { hour12: false }) }}</time>
          <code>{{ e.action }}</code> {{ e.result }}</li>
      </ul>
    </div>
  </div>
  <div v-else-if="!msg" class="dex-card box">正在翻阅图鉴…</div>
</template>

<style scoped>
.detail { display: grid; gap: 16px; }
.box { padding: 14px 18px; }
h3 { margin: 0 0 10px; }
small { color: var(--dex-ink-soft); font-weight: 600; }
dl { display: grid; grid-template-columns: 120px 1fr; gap: 6px 12px; margin: 0; font-size: 14px; }
dt { color: var(--dex-ink-soft); font-weight: 700; }
dd { margin: 0; font-weight: 700; }
.note { font-size: 12px; color: var(--dex-ink-soft); margin: 12px 0 0; }
ul { list-style: none; padding: 0; margin: 0; font-size: 13px; }
li { padding: 4px 0; border-bottom: 2px dashed #eceaf0; }
time { color: var(--dex-ink-soft); margin-right: 8px; font-variant-numeric: tabular-nums; }
.err { color: var(--dex-red); font-weight: 700; }
</style>
