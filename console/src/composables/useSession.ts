import { ref } from 'vue'
import { login as apiLogin, logout as apiLogout, restoreSession, type ApiError } from '../api/bridge'

/** 控制台登录状态（全局单例）；CSRF token 仍只保存在 api/bridge 模块内存中。 */
const loggedIn = ref<boolean | null>(null)
const error = ref('')

export function describeError(e: unknown): string {
  const err = e as Partial<ApiError>
  if (err?.code === 'UNAUTHENTICATED') return '口令不对，再试一次吧'
  if (err?.code === 'RATE_LIMITED') return '尝试太频繁了，稍等一会儿'
  if (err?.code === 'LEASE_OCCUPIED') return '这个应用正在别处出战（另一个页面或设备正在使用）'
  return err?.code ? `${err.code}（HTTP ${err.status}）` : '连不上平台服务'
}

export function useSession() {
  async function check() {
    try {
      loggedIn.value = await restoreSession()
    } catch (e) {
      loggedIn.value = false
      error.value = describeError(e)
    }
  }
  async function login(password: string) {
    error.value = ''
    try {
      await apiLogin(password)
      loggedIn.value = true
    } catch (e) {
      error.value = describeError(e)
      throw e
    }
  }
  async function logout() {
    await apiLogout().catch(() => undefined)
    loggedIn.value = false
  }
  function expired() {
    loggedIn.value = false
  }
  return { loggedIn, error, check, login, logout, expired }
}
