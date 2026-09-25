type TokenResponse = { access_token: string; refresh_token?: string; expires_in?: number }

const authorizationEndpoint = import.meta.env.VITE_OIDC_AUTHORIZATION_ENDPOINT as string | undefined
const tokenEndpoint = import.meta.env.VITE_OIDC_TOKEN_ENDPOINT as string | undefined
const clientId = import.meta.env.VITE_OIDC_CLIENT_ID as string | undefined
const scopes = (import.meta.env.VITE_OIDC_SCOPES as string | undefined)
  ?? 'openid profile offline_access admin operator finance auditor support'

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

function storeTokens(tokens: TokenResponse) {
  sessionStorage.setItem('access_token', tokens.access_token)
  if (tokens.refresh_token) sessionStorage.setItem('refresh_token', tokens.refresh_token)
  const tokenClaims = claims(tokens.access_token)
  const tenants = tokenClaims.tenant_ids
  if (Array.isArray(tenants) && tenants.length > 0) sessionStorage.setItem('tenant_id', String(tenants[0]))
}

async function exchange(parameters: URLSearchParams) {
  if (!tokenEndpoint || !clientId) throw new Error('管理端 OIDC 尚未配置')
  parameters.set('client_id', clientId)
  const response = await fetch(tokenEndpoint, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: parameters
  })
  if (!response.ok) throw new Error('登录令牌交换失败')
  storeTokens(await response.json() as TokenResponse)
}

export async function initializeAuth() {
  if (!authorizationEndpoint || !tokenEndpoint || !clientId) {
    throw new Error('管理端 OIDC 尚未完整配置')
  }
  const query = new URLSearchParams(location.search)
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
  if (!token) return false
  const expiry = Number(claims(token).exp ?? 0)
  if (expiry > Date.now() / 1000 + 30) return true
  const refreshToken = sessionStorage.getItem('refresh_token')
  if (!refreshToken) { logout(); return false }
  try {
    await exchange(new URLSearchParams({ grant_type: 'refresh_token', refresh_token: refreshToken }))
    return true
  } catch {
    logout(); return false
  }
}

export async function login() {
  if (!authorizationEndpoint || !clientId) throw new Error('管理端 OIDC 尚未配置')
  const verifier = randomValue(48)
  const state = randomValue()
  sessionStorage.setItem('oidc_verifier', verifier); sessionStorage.setItem('oidc_state', state)
  const parameters = new URLSearchParams({ response_type: 'code', client_id: clientId,
    redirect_uri: redirectUri(), scope: scopes, state, code_challenge: await challenge(verifier),
    code_challenge_method: 'S256' })
  location.assign(`${authorizationEndpoint}?${parameters}`)
}

export function logout() {
  for (const key of ['access_token', 'refresh_token', 'tenant_id', 'oidc_state', 'oidc_verifier']) {
    sessionStorage.removeItem(key)
  }
}
