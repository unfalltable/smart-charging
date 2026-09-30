type AdminTokenResponse = {
  accessToken: string
  refreshToken: string | null
  expiresInSeconds: number
  mustChangePassword: boolean
}
export type AuthState = { authenticated: boolean; mustChangePassword: boolean }

const apiBase = import.meta.env?.VITE_API_BASE ?? '/api/v1'
let refreshInFlight: Promise<string> | null = null
let sessionGeneration = 0
export const getSessionGeneration = () => sessionGeneration

export class AuthenticationError extends Error {
  status: number
  constructor(message: string, status: number) { super(message); this.status = status }
}

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

export function clearTokens(notify = false) {
  sessionGeneration += 1
  for (const key of ['access_token', 'refresh_token', 'tenant_id']) sessionStorage.removeItem(key)
  if (notify) window.dispatchEvent(new CustomEvent('admin-auth-expired'))
}

function storeTokens(tokens: AdminTokenResponse, resetTenant = false): AuthState {
  if (!tokens.accessToken || !Number.isFinite(tokens.expiresInSeconds)) throw new Error('登录响应不完整，请重试')
  sessionStorage.setItem('access_token', tokens.accessToken)
  if (tokens.refreshToken) sessionStorage.setItem('refresh_token', tokens.refreshToken)
  else sessionStorage.removeItem('refresh_token')
  if (resetTenant) sessionStorage.removeItem('tenant_id')
  return { authenticated: true, mustChangePassword: tokens.mustChangePassword }
}

async function jsonRequest<T>(path: string, body: unknown, token?: string): Promise<T> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 15000)
  try {
    const response = await fetch(`${apiBase}${path}`, {
      method: 'POST',
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      body: JSON.stringify(body),
      signal: controller.signal
    })
    if (!response.ok) {
      const error = await response.json().catch(() => null) as { message?: string } | null
      throw new AuthenticationError(error?.message ?? `登录请求失败（${response.status}）`, response.status)
    }
    if (response.status === 204) return undefined as T
    return response.json() as Promise<T>
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw new Error('请求超时，请检查网络后重试')
    throw error
  } finally { clearTimeout(timer) }
}

export async function ensureAccessToken(force = false, rejectedToken?: string): Promise<string | null> {
  const token = sessionStorage.getItem('access_token')
  if (!token) return null
  if (force && rejectedToken && token !== rejectedToken) return token
  try { if (!force && tokenState(token).authenticated) return token } catch { /* refresh malformed storage */ }
  if (refreshInFlight) return refreshInFlight
  const refreshToken = sessionStorage.getItem('refresh_token')
  if (!refreshToken) { clearTokens(true); throw new AuthenticationError('登录状态已失效，请重新登录', 401) }
  const generation = sessionGeneration
  refreshInFlight = jsonRequest<AdminTokenResponse>('/auth/admin/refresh', { refreshToken })
    .then((tokens) => {
      if (generation !== sessionGeneration) throw new AuthenticationError('登录状态已变更，请重新登录', 401)
      storeTokens(tokens)
      return tokens.accessToken
    }).catch((error) => {
      if (generation === sessionGeneration && error instanceof AuthenticationError && [401, 403].includes(error.status)) clearTokens(true)
      throw error
    }).finally(() => { refreshInFlight = null })
  return refreshInFlight
}

export async function initializeAuth(): Promise<AuthState> {
  const token = await ensureAccessToken()
  if (!token) return { authenticated: false, mustChangePassword: false }
  try {
    return tokenState(token)
  } catch {
    clearTokens()
    return { authenticated: false, mustChangePassword: false }
  }
}

export async function login(username?: string, password?: string): Promise<AuthState> {
  if (!username || !password) throw new Error('请输入用户名和密码')
  const generation = ++sessionGeneration
  const tokens = await jsonRequest<AdminTokenResponse>('/auth/admin/login', { username, password })
  if (generation !== sessionGeneration) throw new AuthenticationError('登录已取消', 401)
  return storeTokens(tokens, true)
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<AuthState> {
  const token = await ensureAccessToken()
  if (!token) throw new Error('登录状态已失效，请重新登录')
  const generation = sessionGeneration
  const tokens = await jsonRequest<AdminTokenResponse>('/auth/admin/password', { currentPassword, newPassword }, token)
  if (generation !== sessionGeneration) throw new AuthenticationError('登录状态已变更，请重新登录', 401)
  return storeTokens(tokens)
}

export async function logout() {
  const token = sessionStorage.getItem('access_token')
  const refreshToken = sessionStorage.getItem('refresh_token')
  clearTokens()
  if (token) {
    try { await jsonRequest<void>('/auth/admin/logout', { refreshToken }, token) } catch { /* clear locally */ }
  }
}
