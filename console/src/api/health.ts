export interface PlatformHealth {
  platform: string
  shortName: string
  version: string
  status: string
  adapterProtocol: string
  registeredInstances: number
  note: string
}

/** 调用 mmab-server 骨架的只读健康接口；失败返回 null（例如未启动服务端）。 */
export async function fetchHealth(): Promise<PlatformHealth | null> {
  try {
    const res = await fetch('/__mmab/api/v1/health', { credentials: 'same-origin' })
    return res.ok ? ((await res.json()) as PlatformHealth) : null
  } catch {
    return null
  }
}
