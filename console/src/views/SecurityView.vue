<script setup lang="ts">
import DexIcon from '../components/DexIcon.vue'
import { onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import { listAudit, revokeAll, type ApiError, type AuditRow } from '../api/bridge'
import { describeError, useSession } from '../composables/useSession'

const { expired } = useSession()
const rows = ref<AuditRow[]>([])
const next = ref<number | null>(null)
const filterInstance = ref('')
const filterAction = ref('')
const loading = ref(false)
const msg = ref('')
const open = ref(new Set<number>())
function toggle(seq: number) {
  const s = new Set(open.value)
  if (s.has(seq)) s.delete(seq)
  else s.add(seq)
  open.value = s
}

const ACTIONS = [
  { v: '', l: '全部动作' }, { v: 'auth.', l: '登录 / 注销' }, { v: 'ticket.', l: '开窗' },
  { v: 'transport.', l: '连接' }, { v: 'browser.', l: '页面诊断' }, { v: 'adapter.', l: '适配器' },
]

async function load(more = false) {
  loading.value = true
  try {
    const r = await listAudit({ limit: 100, before: more ? next.value ?? undefined : undefined,
      instance: filterInstance.value || undefined, action: filterAction.value || undefined })
    rows.value = more ? rows.value.concat(r.events) : r.events
    next.value = r.events.length === 100 ? r.nextBefore : null
  } catch (e) {
    if ((e as ApiError).status === 401) expired()
    else msg.value = describeError(e)
  } finally {
    loading.value = false
  }
}

function fmt(at: string | number) {
  return new Date(at).toLocaleString('zh-CN', { hour12: false })
}
function ok(r: AuditRow) {
  return r.result === 'OK' || /^(load|unload|close)$/.test(r.result)
}

async function doRevokeAll() {
  try {
    const { value } = await ElMessageBox.prompt(
      '所有设备上的控制台和应用页面都会立即退出，需要重新登录。请输入平台口令确认：', '注销所有会话',
      { inputType: 'password', confirmButtonText: '全部注销', cancelButtonText: '算了', inputPattern: /.+/, inputErrorMessage: '请输入口令' })
    await revokeAll(value)
    expired()
  } catch (e) {
    if (e === 'cancel' || e === 'close') return
    msg.value = describeError(e)
  }
}

onMounted(() => load())
</script>

<template>
  <div class="sec">
    <div class="dex-card intro">
      <div>
        <h3 class="dex-title"><DexIcon name="shield" :size="22" /> 安全与审计</h3>
        <p>审计只记录元数据：谁、何时、对哪个应用、做了什么、结果如何。不记录消息内容、口令、Cookie 或票据，默认保留 30 天。</p>
      </div>
      <button class="go-btn danger" @click="doRevokeAll"><DexIcon name="broom" :size="16" /> 注销所有会话</button>
    </div>
    <p v-if="msg" class="err">{{ msg }}</p>

    <div class="filters">
      <select v-model="filterInstance" @change="load()">
        <option value="">全部应用</option>
        <option value="qwen-01">千问办公</option>
        <option value="codex-01">Codex</option>
        <option value="mock-01">Mock</option>
      </select>
      <select v-model="filterAction" @change="load()">
        <option v-for="a in ACTIONS" :key="a.v" :value="a.v">{{ a.l }}</option>
      </select>
      <button class="go-btn ghost small" @click="load()">刷新</button>
    </div>

    <div class="dex-card table-wrap">
      <table>
        <thead><tr><th>时间</th><th>动作</th><th>应用</th><th>结果</th><th>细节</th></tr></thead>
        <tbody>
          <tr v-for="r in rows" :key="r.seq" :class="{ warn: !ok(r), expanded: open.has(r.seq) }" @click="toggle(r.seq)">
            <td class="t">{{ fmt(r.at) }}</td>
            <td><code>{{ r.action }}</code></td>
            <td>{{ r.instance }}</td>
            <td><span class="res" :class="ok(r) ? 'good' : 'bad'">{{ r.result }}</span></td>
            <td class="d" :title="r.detail">{{ r.detail }}</td>
          </tr>
          <tr v-if="!rows.length && !loading"><td colspan="5" class="none">暂无记录</td></tr>
        </tbody>
      </table>
      <div class="count">已显示 {{ rows.length }} 条 · 点击一行查看完整细节</div>
      <div class="more"><button v-if="next" class="go-btn ghost small" :disabled="loading" @click="load(true)">更早的记录</button></div>
    </div>
  </div>
</template>

<style scoped>
.intro { display: flex; justify-content: space-between; align-items: center; gap: 16px; padding: 14px 18px; margin-bottom: 14px; }
.intro h3 { margin: 0 0 4px; display: flex; align-items: center; gap: 6px; }
.danger { display: inline-flex; align-items: center; gap: 6px; }
.intro p { margin: 0; font-size: 13px; color: var(--dex-ink-soft); }
.danger { background: var(--dex-red); color: #fff; white-space: nowrap; }
.err { color: var(--dex-red); font-weight: 700; }
.filters { display: flex; gap: 10px; margin-bottom: 12px; }
select { padding: 6px 10px; border: 3px solid var(--dex-line); border-radius: 10px; font-weight: 700; background: #fff; }
.small { padding: 4px 12px; font-size: 12px; }
.table-wrap { padding: 6px 10px 10px; overflow-x: auto; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th { text-align: left; padding: 8px 6px; border-bottom: 3px solid var(--dex-line); white-space: nowrap; }
td { padding: 8px 6px; border-bottom: 2px dashed #eceaf0; vertical-align: top; }
tbody tr { cursor: pointer; }
tbody tr:hover { background: #fff8e5; }
tr.warn td:first-child { box-shadow: inset 4px 0 0 var(--dex-hp-red); }
tr.expanded .d { white-space: normal; word-break: break-all; overflow: visible; }
.count { font-size: 12px; color: var(--dex-ink-soft); text-align: center; margin-top: 8px; }
.t { white-space: nowrap; font-variant-numeric: tabular-nums; color: var(--dex-ink-soft); }
.d { max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: ui-monospace, Menlo, monospace; font-size: 12px; }
.res { font-weight: 800; padding: 0 8px; border-radius: 999px; border: 2px solid var(--dex-line); font-size: 12px; }
.res.good { background: #e3fbe7; }
.res.bad { background: #ffe1d9; }
.none { text-align: center; color: var(--dex-ink-soft); padding: 20px; }
.more { text-align: center; margin-top: 10px; }
</style>
