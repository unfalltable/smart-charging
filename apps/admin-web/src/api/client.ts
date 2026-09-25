export type DashboardSummary = {
  onlineDevices: number
  totalDevices: number
  availableConnectors: number
  activeOrders: number
  todayRevenueMinor: number
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
