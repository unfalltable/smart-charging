import { clearTokens, ensureAccessToken, getSessionGeneration } from '../auth'

export type DashboardSummary = {
  onlineDevices: number
  totalDevices: number
  availableConnectors: number
  activeOrders: number
  todayRevenueMinor: number
}

export type TenantAccess = {
  id: string
  code: string
  displayName: string
  roles: string[]
}

export type SessionContext = {
  subject: string
  username: string
  displayName: string
  platformAdministrator: boolean
  tenants: TenantAccess[]
}

const apiBase = import.meta.env?.VITE_API_BASE ?? '/api/v1'

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const tenantId = sessionStorage.getItem('tenant_id') ?? ''
  const generation = getSessionGeneration()
  const assertSession = () => {
    if (generation !== getSessionGeneration() || tenantId !== (sessionStorage.getItem('tenant_id') ?? '')) {
      throw new Error('账号或租户已切换，请重新加载数据')
    }
  }
  const token = await ensureAccessToken()
  async function send(accessToken: string | null): Promise<Response> {
    assertSession()
    const controller = new AbortController()
    const abort = () => controller.abort(init.signal?.reason)
    if (init.signal?.aborted) abort()
    else init.signal?.addEventListener('abort', abort, { once: true })
    const timer = setTimeout(() => controller.abort(), 20000)
    const headers = new Headers(init.headers)
    headers.set('Accept', 'application/json')
    if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
    if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)
    if (tenantId) headers.set('X-Tenant-Id', tenantId)
    try {
      return await fetch(`${apiBase}${path}`, { ...init, headers, signal: controller.signal })
    } catch (error) {
      if (controller.signal.aborted && !init.signal?.aborted) throw new Error('服务请求超时，请刷新后重试')
      throw error
    } finally { clearTimeout(timer); init.signal?.removeEventListener('abort', abort) }
  }
  let response = await send(token)
  assertSession()
  if (response.status === 401 && token) response = await send(await ensureAccessToken(true, token))
  assertSession()
  if (!response.ok) {
    if (response.status === 401) clearTokens(true)
    const error = await response.json().catch(() => null) as { message?: string; violations?: { field?: string; message: string }[] } | null
    const message = error?.violations?.map(item => `${item.field ? `${item.field}：` : ''}${item.message}`).join('；') || error?.message
    throw new Error(message ?? `服务请求失败（${response.status}），请重试`)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const getJson = <T>(path: string) => apiRequest<T>(path)
export const postJson = <T>(path: string, body: unknown, idempotencyKey?: string) => apiRequest<T>(path, {
  method: 'POST',
  ...(idempotencyKey ? { headers: { 'Idempotency-Key': idempotencyKey } } : {}),
  body: JSON.stringify(body)
})
export const patchJson = <T>(path: string, body: unknown) => apiRequest<T>(path, {
  method: 'PATCH',
  body: JSON.stringify(body)
})

export async function loadSessionContext(): Promise<SessionContext> {
  const session = await apiRequest<SessionContext>('/session')
  const previous = sessionStorage.getItem('tenant_id')
  const selected = session.tenants.find((tenant) => tenant.id === previous) ?? session.tenants[0]
  if (!selected) {
    sessionStorage.removeItem('tenant_id')
    return session
  }
  sessionStorage.setItem('tenant_id', selected.id)
  return session
}

export function selectTenant(tenantId: string, session: SessionContext) {
  if (!session.tenants.some((tenant) => tenant.id === tenantId)) {
    throw new Error('所选租户不在当前账号的授权范围内')
  }
  sessionStorage.setItem('tenant_id', tenantId)
}
