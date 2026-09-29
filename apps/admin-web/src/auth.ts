type AdminTokenResponse = {
  accessToken: string
  refreshToken: string | null
  expiresInSeconds: number
  mustChangePassword: boolean
}
export type AuthState = { authenticated: boolean; mustChangePassword: boolean }

const apiBase = import.meta.env.VITE_API_BASE ?? '/api/v1'

function claims(token: string): Record<string, unknown> {
  const payload = token.split('.')[1]
  if (!payload) return {}
  const normalized = payload.replace(/-/g, '+').replace(/_/g, '/')
  return JSON.parse(decodeURIComponent(Array.from(atob(normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=')))
    .map((value) => `%${value.charCodeAt(0).toString(16).padStart(2, '0')}`).join(''))) as Record<string, unknown>
}

function tokenState(token: string): AuthState {
  const tokenClaims = claims(token)
  const scope = String(tokenClaims.scope ?? '').split(' ')
  return {
    authenticated: Number(tokenClaims.exp ?? 0) > Date.now() / 1000 + 30,
    mustChangePassword: scope.includes('password_change')
  }
}

function clearTokens() {
  for (const key of ['access_token', 'refresh_token', 'tenant_id']) sessionStorage.removeItem(key)
}

function storeTokens(tokens: AdminTokenResponse): AuthState {
  sessionStorage.setItem('access_token', tokens.accessToken)
  if (tokens.refreshToken) sessionStorage.setItem('refresh_token', tokens.refreshToken)
  else sessionStorage.removeItem('refresh_token')
  sessionStorage.removeItem('tenant_id')
  return { authenticated: true, mustChangePassword: tokens.mustChangePassword }
}

async function jsonRequest<T>(path: string, body: unknown, token?: string): Promise<T> {
  const response = await fetch(`${apiBase}${path}`, {
    method: 'POST',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify(body)
  })
  if (!response.ok) {
    const error = await response.json().catch(() => null) as { message?: string } | null
    throw new Error(error?.message ?? `登录请求失败（${response.status}）`)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export async function initializeAuth(): Promise<AuthState> {
  const token = sessionStorage.getItem('access_token')
  if (!token) return { authenticated: false, mustChangePassword: false }
  let current: AuthState
  try {
    current = tokenState(token)
  } catch {
    clearTokens()
    return { authenticated: false, mustChangePassword: false }
  }
  if (current.authenticated) return current
  const refreshToken = sessionStorage.getItem('refresh_token')
  if (!refreshToken) { clearTokens(); return { authenticated: false, mustChangePassword: false } }
  try {
    return storeTokens(await jsonRequest<AdminTokenResponse>('/auth/admin/refresh', { refreshToken }))
  } catch {
    clearTokens(); return { authenticated: false, mustChangePassword: false }
  }
}

export async function login(username?: string, password?: string): Promise<AuthState> {
  if (!username || !password) throw new Error('请输入用户名和密码')
  return storeTokens(await jsonRequest<AdminTokenResponse>('/auth/admin/login', { username, password }))
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<AuthState> {
  const token = sessionStorage.getItem('access_token')
  if (!token) throw new Error('登录状态已失效，请重新登录')
  return storeTokens(await jsonRequest<AdminTokenResponse>('/auth/admin/password',
    { currentPassword, newPassword }, token))
}

export async function logout() {
  const token = sessionStorage.getItem('access_token')
  const refreshToken = sessionStorage.getItem('refresh_token')
  if (token) {
    try { await jsonRequest<void>('/auth/admin/logout', { refreshToken }, token) } catch { /* clear locally */ }
  }
  clearTokens()
}
