type OidcTokenResponse = { access_token: string; refresh_token?: string; expires_in?: number }
type AdminTokenResponse = {
  accessToken: string
  refreshToken: string | null
  expiresInSeconds: number
  mustChangePassword: boolean
}
export type AuthState = { authenticated: boolean; mustChangePassword: boolean }

const apiBase = import.meta.env.VITE_API_BASE ?? '/api/v1'
const authMode = (import.meta.env.VITE_AUTH_MODE as string | undefined) ?? 'database'
const authorizationEndpoint = import.meta.env.VITE_OIDC_AUTHORIZATION_ENDPOINT as string | undefined
const tokenEndpoint = import.meta.env.VITE_OIDC_TOKEN_ENDPOINT as string | undefined
const clientId = import.meta.env.VITE_OIDC_CLIENT_ID as string | undefined
const scopes = (import.meta.env.VITE_OIDC_SCOPES as string | undefined) ?? 'openid'

export const usesExternalIdentity = authMode === 'external'

function redirectUri() {
  return (import.meta.env.VITE_OIDC_REDIRECT_URI as string | undefined)
    ?? `${location.origin}${location.pathname}`
}

function encode(bytes: Uint8Array) {
  let binary = ''
  bytes.forEach((byte) => { binary += String.fromCharCode(byte) })
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function randomValue(size = 32) {
  const bytes = new Uint8Array(size)
  crypto.getRandomValues(bytes)
  return encode(bytes)
}

async function challenge(verifier: string) {
  return encode(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier))))
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

function clearTokens() {
  for (const key of ['access_token', 'refresh_token', 'tenant_id', 'oidc_state', 'oidc_verifier']) {
    sessionStorage.removeItem(key)
  }
}

function storeAdminTokens(tokens: AdminTokenResponse): AuthState {
  sessionStorage.setItem('access_token', tokens.accessToken)
  if (tokens.refreshToken) sessionStorage.setItem('refresh_token', tokens.refreshToken)
  else sessionStorage.removeItem('refresh_token')
  sessionStorage.removeItem('tenant_id')
  return { authenticated: true, mustChangePassword: tokens.mustChangePassword }
}

function storeOidcTokens(tokens: OidcTokenResponse) {
  sessionStorage.setItem('access_token', tokens.access_token)
  if (tokens.refresh_token) sessionStorage.setItem('refresh_token', tokens.refresh_token)
  sessionStorage.removeItem('tenant_id')
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

async function exchange(parameters: URLSearchParams) {
  if (!tokenEndpoint || !clientId) throw new Error('管理端外部 OIDC 尚未配置')
  parameters.set('client_id', clientId)
  const response = await fetch(tokenEndpoint, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: parameters
  })
  if (!response.ok) throw new Error('外部身份令牌交换失败')
  storeOidcTokens(await response.json() as OidcTokenResponse)
}

async function initializeExternal(): Promise<AuthState> {
  if (!authorizationEndpoint || !tokenEndpoint || !clientId) {
    throw new Error('管理端外部 OIDC 尚未完整配置')
  }
  const query = new URLSearchParams(location.search)
  const authorizationError = query.get('error')
  if (authorizationError) {
    const description = query.get('error_description') ?? authorizationError
    sessionStorage.removeItem('oidc_state')
    sessionStorage.removeItem('oidc_verifier')
    history.replaceState({}, document.title, location.pathname)
    throw new Error(`企业账号登录失败：${description}`)
  }
  const code = query.get('code')
  if (code) {
    const state = query.get('state')
    if (!state || state !== sessionStorage.getItem('oidc_state')) throw new Error('登录状态校验失败')
    const verifier = sessionStorage.getItem('oidc_verifier')
    if (!verifier) throw new Error('登录验证信息已失效')
    await exchange(new URLSearchParams({ grant_type: 'authorization_code', code,
      redirect_uri: redirectUri(), code_verifier: verifier }))
    sessionStorage.removeItem('oidc_state'); sessionStorage.removeItem('oidc_verifier')
    history.replaceState({}, document.title, location.pathname)
  }
  const token = sessionStorage.getItem('access_token')
  if (!token) return { authenticated: false, mustChangePassword: false }
  if (tokenState(token).authenticated) return { authenticated: true, mustChangePassword: false }
  const refreshToken = sessionStorage.getItem('refresh_token')
  if (!refreshToken) { clearTokens(); return { authenticated: false, mustChangePassword: false } }
  try {
    await exchange(new URLSearchParams({ grant_type: 'refresh_token', refresh_token: refreshToken }))
    return { authenticated: true, mustChangePassword: false }
  } catch {
    clearTokens(); return { authenticated: false, mustChangePassword: false }
  }
}

export async function initializeAuth(): Promise<AuthState> {
  if (usesExternalIdentity) return initializeExternal()
  const token = sessionStorage.getItem('access_token')
  if (!token) return { authenticated: false, mustChangePassword: false }
  const current = tokenState(token)
  if (current.authenticated) return current
  const refreshToken = sessionStorage.getItem('refresh_token')
  if (!refreshToken) { clearTokens(); return { authenticated: false, mustChangePassword: false } }
  try {
    return storeAdminTokens(await jsonRequest<AdminTokenResponse>('/auth/admin/refresh', { refreshToken }))
  } catch {
    clearTokens(); return { authenticated: false, mustChangePassword: false }
  }
}

export async function login(username?: string, password?: string): Promise<AuthState> {
  if (!usesExternalIdentity) {
    if (!username || !password) throw new Error('请输入用户名和密码')
    return storeAdminTokens(await jsonRequest<AdminTokenResponse>('/auth/admin/login', { username, password }))
  }
  if (!authorizationEndpoint || !clientId) throw new Error('管理端外部 OIDC 尚未配置')
  const verifier = randomValue(48)
  const state = randomValue()
  sessionStorage.setItem('oidc_verifier', verifier); sessionStorage.setItem('oidc_state', state)
  const parameters = new URLSearchParams({ response_type: 'code', client_id: clientId,
    redirect_uri: redirectUri(), scope: scopes, state, code_challenge: await challenge(verifier),
    code_challenge_method: 'S256' })
  location.assign(`${authorizationEndpoint}?${parameters}`)
  return { authenticated: false, mustChangePassword: false }
}

export async function changePassword(currentPassword: string, newPassword: string): Promise<AuthState> {
  const token = sessionStorage.getItem('access_token')
  if (!token) throw new Error('登录状态已失效，请重新登录')
  return storeAdminTokens(await jsonRequest<AdminTokenResponse>('/auth/admin/password',
    { currentPassword, newPassword }, token))
}

export async function logout() {
  const token = sessionStorage.getItem('access_token')
  const refreshToken = sessionStorage.getItem('refresh_token')
  if (!usesExternalIdentity && token) {
    try { await jsonRequest<void>('/auth/admin/logout', { refreshToken }, token) } catch { /* local cleanup still applies */ }
  }
  clearTokens()
}
