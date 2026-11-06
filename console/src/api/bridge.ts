// mmab-server 控制台 API（M1）。CSRF token 只保存在本模块内存里，不进 Pinia / localStorage；
// 开窗票据只在一次表单 POST 中使用，用完即丢（设计 §5.3、§3 管理前端约束）。

const API = '/__mmab/api/v1'
let csrfToken: string | null = null

export interface ApiError {
  status: number
  code: string
}

export interface LiveInstance {
  id: string
  appId: string
  displayName: string
  strategy: string
  enabled: boolean
  port: number
  state: string
  blockedReason: string | null
  generation: number
  openable: boolean
  profileId?: string
  risk?: string
  capabilities?: Record<string, string>
  lease: { held: boolean; mine: boolean; leaseId?: string; expiresAt?: string }
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (method !== 'GET' && csrfToken) headers['X-MMAB-CSRF'] = csrfToken
  const res = await fetch(API + path, {
    method,
    headers,
    credentials: 'same-origin',
    cache: 'no-store',
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) {
    let code = `HTTP_${res.status}`
    try {
      code = (await res.json()).error?.code ?? code
    } catch {
      // 非 JSON 错误体
    }
    throw { status: res.status, code } satisfies ApiError
  }
  return (res.status === 204 ? undefined : await res.json()) as T
}

/** 已登录返回 true；未登录（401）返回 false。 */
export async function restoreSession(): Promise<boolean> {
  try {
    const s = await call<{ csrfToken: string }>('GET', '/auth/session')
    csrfToken = s.csrfToken
    return true
  } catch (e) {
    if ((e as ApiError).status === 401) return false
    throw e
  }
}

export async function login(password: string): Promise<void> {
  const s = await call<{ csrfToken: string }>('POST', '/auth/login', { password })
  csrfToken = s.csrfToken
}

export async function logout(): Promise<void> {
  try {
    await call<void>('POST', '/auth/logout')
  } finally {
    csrfToken = null
  }
}

export function listInstances(): Promise<LiveInstance[]> {
  return call<LiveInstance[]>('GET', '/instances')
}

/**
 * 开窗：签发一次性票据（同时原子获取 / 复用独占租约），然后以表单 POST 在新标签提交到实例端口的
 * 固定 exchange 地址。票据不进 URL、不进存储，只存在于这次提交的表单里。
 */
export async function openInstance(id: string): Promise<void> {
  const t = await call<{ ticket: string; exchangeUrl: string }>('POST', `/instances/${encodeURIComponent(id)}/launch-tickets`)
  const form = document.createElement('form')
  form.method = 'POST'
  form.action = t.exchangeUrl
  form.target = '_blank'
  // 只用 noopener；不要 noreferrer（会让浏览器发出 Origin: null，被服务端拒绝）
  form.rel = 'noopener'
  const input = document.createElement('input')
  input.type = 'hidden'
  input.name = 'ticket'
  input.value = t.ticket
  form.appendChild(input)
  document.body.appendChild(form)
  try {
    form.submit()
  } finally {
    form.remove()
  }
}
