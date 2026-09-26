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
  platformAdministrator: boolean
  tenants: TenantAccess[]
}

const apiBase = import.meta.env.VITE_API_BASE ?? '/api/v1'

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = sessionStorage.getItem('access_token')
  const tenantId = sessionStorage.getItem('tenant_id') ?? ''
  const response = await fetch(`${apiBase}${path}`, {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init.body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}),
      ...init.headers
    }
  })
  if (!response.ok) {
    if (response.status === 401) {
      sessionStorage.removeItem('access_token')
      sessionStorage.removeItem('refresh_token')
    }
    const error = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(error?.message ?? `API request failed (${response.status})`)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export const getJson = <T>(path: string) => apiRequest<T>(path)
export const postJson = <T>(path: string, body: unknown) => apiRequest<T>(path, {
  method: 'POST',
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
    throw new Error('当前账号没有可访问的生产租户，请先完成租户开通')
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
