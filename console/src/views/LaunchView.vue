<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { listInstances, login, logout, openInstance, restoreSession, type ApiError, type LiveInstance } from '../api/bridge'

const loggedIn = ref<boolean | null>(null)
const password = ref('')
const busy = ref(false)
const error = ref('')
const instances = ref<LiveInstance[]>([])

function describe(e: unknown): string {
  const err = e as Partial<ApiError>
  return err?.code ? `${err.code}（HTTP ${err.status}）` : '服务端未连接'
}

async function refresh() {
  instances.value = await listInstances()
}

onMounted(async () => {
  try {
    loggedIn.value = await restoreSession()
    if (loggedIn.value) await refresh()
  } catch (e) {
    loggedIn.value = false
    error.value = describe(e)
  }
})

async function doLogin() {
  busy.value = true
  error.value = ''
  try {
    await login(password.value)
    loggedIn.value = true
    await refresh()
  } catch (e) {
    error.value = describe(e)
  } finally {
    password.value = ''
    busy.value = false
  }
}

async function doLogout() {
  await logout().catch(() => undefined)
  loggedIn.value = false
  instances.value = []
}

async function open(id: string) {
  error.value = ''
  try {
    await openInstance(id)
    await refresh()
  } catch (e) {
    error.value = describe(e)
  }
}
</script>

<template>
  <el-alert
    type="info"
    :closable="false"
    show-icon
    class="note"
    title="M1：真实接口。开窗会签发 30 秒一次性票据并占用该实例的独占租约；当前只有离线 mock 适配器，可用于验证链路。"
  />
  <el-alert v-if="error" type="error" :closable="false" show-icon class="note" :title="error" />

  <el-card v-if="loggedIn === false" class="login">
    <template #header>平台操作者登录</template>
    <el-form @submit.prevent="doLogin">
      <el-form-item label="口令">
        <el-input v-model="password" type="password" autocomplete="current-password" show-password />
      </el-form-item>
      <el-button type="primary" native-type="submit" :loading="busy" :disabled="!password">登录</el-button>
    </el-form>
  </el-card>

  <template v-else-if="loggedIn">
    <div class="toolbar">
      <el-button size="small" @click="refresh">刷新</el-button>
      <el-button size="small" @click="doLogout">注销</el-button>
    </div>
    <el-table :data="instances" size="small">
      <el-table-column prop="id" label="实例" width="120" />
      <el-table-column prop="displayName" label="名称" />
      <el-table-column prop="port" label="端口" width="80" />
      <el-table-column prop="state" label="实态" width="130" />
      <el-table-column prop="generation" label="generation" width="100" />
      <el-table-column label="租约" width="120">
        <template #default="{ row }">{{ row.lease.mine ? '我持有' : row.lease.held ? '被占用' : '空闲' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="120">
        <template #default="{ row }">
          <el-button size="small" type="primary" :disabled="!row.openable || (row.lease.held && !row.lease.mine)"
            @click="open(row.id)">打开应用</el-button>
        </template>
      </el-table-column>
    </el-table>
  </template>
</template>

<style scoped>
.note { margin-bottom: 12px; }
.login { max-width: 420px; }
.toolbar { margin-bottom: 8px; display: flex; gap: 8px; }
</style>
