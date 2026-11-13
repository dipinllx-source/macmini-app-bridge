<script setup lang="ts">
// 打开实例前的检查结果：不符合预期时说明原因，并提供“恢复”。恢复可能重启平台本身——期间请求会失败，
// 平台回来后重新取会话（CSRF 令牌会变）、再检查一次，通过后让用户点“出发！”（新的点击，避免被拦截弹窗）。
import { computed, onBeforeUnmount, ref } from 'vue'
import {
  preflightInstance, recoverStatus, restoreSession, startRecover,
  type ApiError, type LiveInstance, type PreflightResult,
} from '../api/bridge'
import { describeError } from '../composables/useSession'

const props = defineProps<{ inst: LiveInstance; result: PreflightResult }>()
const emit = defineEmits<{ close: []; open: []; recovered: []; expired: [] }>()

type Phase = 'problems' | 'recovering' | 'restarting' | 'done' | 'failed'
const phase = ref<Phase>(props.result.recovering ? 'recovering' : 'problems')
const current = ref<PreflightResult>(props.result)
const logTail = ref<string[]>([])
const error = ref('')
let stopped = false
onBeforeUnmount(() => { stopped = true })

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))
const title = computed(() => ({
  problems: `${props.inst.displayName} 需要先恢复`,
  recovering: '正在恢复…',
  restarting: '引桥正在重启…',
  done: '已恢复',
  failed: '恢复没有完成',
}[phase.value]))

async function recover() {
  error.value = ''
  phase.value = 'recovering'
  try {
    if (!props.result.recovering) await startRecover(props.inst.id)
  } catch (e) {
    error.value = describeError(e)
    phase.value = 'failed'
    return
  }
  const deadline = Date.now() + 5 * 60 * 1000
  let lost = false
  while (!stopped && Date.now() < deadline) {
    await sleep(2000)
    try {
      if (lost && !(await restoreSession())) {
        emit('expired')
        return
      }
      const st = await recoverStatus(props.inst.id)
      logTail.value = st.logTail
      if (st.running) continue
      const p = await preflightInstance(props.inst.id)
      current.value = p
      if (p.ok) {
        phase.value = 'done'
        emit('recovered')
        return
      }
      if (st.exitCode !== null || lost) {
        phase.value = 'failed'
        return
      }
    } catch (e) {
      // 恢复命令重启平台：控制台暂时连不上，等它回来
      if ((e as ApiError).status === 401) {
        emit('expired')
        return
      }
      lost = true
      phase.value = 'restarting'
    }
  }
  if (!stopped && (phase.value as Phase) !== 'done') {
    error.value = '等待超时'
    phase.value = 'failed'
  }
}
</script>

<template>
  <div class="mask" @mousedown.self="phase !== 'recovering' && phase !== 'restarting' && emit('close')">
    <div class="dex-card dlg" role="dialog" aria-modal="true">
      <h3 class="dex-title">{{ title }}</h3>

      <template v-if="phase === 'problems' || phase === 'failed'">
        <p v-if="current.installedVersion || current.expectedVersion" class="ver">
          本机版本 <b>{{ current.installedVersion ?? '未知' }}</b>，引桥预期 <b>{{ current.expectedVersion ?? '未知' }}</b>
        </p>
        <ul class="probs">
          <li v-for="p in current.problems" :key="p.code">{{ p.message }}</li>
        </ul>
        <p v-if="phase === 'problems'" class="hint">
          “恢复”会在 Mac 上补提取新版页面资源、启动平台专用实例；版本变了还会重启引桥（打开中的页面断开几秒后自动重连）。
        </p>
        <p v-if="error" class="err">{{ error }}</p>
      </template>

      <template v-else-if="phase === 'recovering' || phase === 'restarting'">
        <p class="hint">
          <span class="spin" aria-hidden="true" />
          {{ phase === 'restarting' ? '引桥正在按新版本重启，回来后会自动再检查一次。' : '正在 Mac 上恢复，通常需要几十秒。' }}
        </p>
      </template>

      <template v-else>
        <p class="ok">检查通过（{{ current.installedVersion }}），可以打开了。</p>
      </template>

      <pre v-if="logTail.length && phase !== 'problems'" class="log">{{ logTail.slice(-8).join('\n') }}</pre>

      <div class="acts">
        <template v-if="phase === 'problems' || phase === 'failed'">
          <button class="go-btn ghost small" @click="emit('close')">取消</button>
          <button class="go-btn ghost small" title="不恢复，直接打开（页面可能白屏或报错）" @click="emit('open')">仍然打开</button>
          <button class="go-btn" @click="recover">{{ phase === 'failed' ? '重试恢复' : '恢复' }}</button>
        </template>
        <template v-else-if="phase === 'done'">
          <button class="go-btn ghost small" @click="emit('close')">稍后</button>
          <button class="go-btn" @click="emit('open')">出发！</button>
        </template>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mask { position: fixed; inset: 0; z-index: 50; background: rgba(43, 45, 66, 0.45); display: grid; place-items: center; padding: 16px; }
.dlg { width: min(560px, 100%); padding: 20px 22px; display: grid; gap: 12px; }
h3 { margin: 0; font-size: 18px; }
.ver { margin: 0; font-size: 14px; color: var(--dex-ink); }
.ver b { font-variant-numeric: tabular-nums; }
.probs { margin: 0; padding-left: 20px; display: grid; gap: 4px; font-weight: 700; word-break: keep-all; overflow-wrap: anywhere; }
.hint { margin: 0; font-size: 13px; color: var(--dex-ink-soft); line-height: 1.6; display: flex; gap: 8px; align-items: flex-start; }
.ok { margin: 0; font-weight: 800; color: #1f8a3a; }
.err { margin: 0; color: var(--dex-red); font-weight: 700; font-size: 13px; }
.log {
  margin: 0; max-height: 150px; overflow: auto; font: 12px/1.5 ui-monospace, Menlo, monospace; background: #f6f5fa;
  border: 2px dashed #dcdbe6; border-radius: 10px; padding: 8px 10px; white-space: pre-wrap; word-break: break-all;
}
.acts { display: flex; justify-content: flex-end; gap: 12px; flex-wrap: wrap; margin-top: 4px; }
.small { padding: 4px 12px; font-size: 12px; }
.spin {
  flex: none; width: 14px; height: 14px; margin-top: 2px; border-radius: 50%; border: 3px solid #dcdbe6;
  border-top-color: var(--dex-red); animation: spin 0.9s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
</style>
