import { beforeEach, test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'
import { AuthenticationError, clearTokens, ensureAccessToken, logout } from '../src/auth.ts'
import { SubmissionKeys } from '../src/submission.ts'
import { parseReconciliationRows } from '../src/reconciliation.ts'
import { prepareOperatorStop } from '../src/orderControl.ts'
import { evaluatePassword } from '../src/passwordPolicy.ts'

const clientSource = readFileSync(new URL('../src/api/client.ts', import.meta.url), 'utf8')
  .replace("'../auth'", JSON.stringify(new URL('../src/auth.ts', import.meta.url).href))
const { postJson } = await import('data:text/javascript;base64,' + Buffer.from(
  ts.transpileModule(clientSource, { compilerOptions: { target: ts.ScriptTarget.ES2023, module: ts.ModuleKind.ESNext } }).outputText
).toString('base64'))

const storage = new Map()
globalThis.sessionStorage = {
  getItem: key => storage.get(key) ?? null,
  setItem: (key, value) => storage.set(key, String(value)),
  removeItem: key => storage.delete(key)
}
globalThis.window = new EventTarget()

const token = seconds => `h.${Buffer.from(JSON.stringify({ exp: Date.now() / 1000 + seconds, scope: 'admin' })).toString('base64url')}.s`
function existingSession(seconds = -1) {
  storage.set('access_token', token(seconds))
  storage.set('refresh_token', 'initial-refresh')
  storage.set('tenant_id', 'selected-tenant')
}
function response(accessToken) {
  return Response.json({ accessToken, refreshToken: 'rotated-refresh', expiresInSeconds: 900, mustChangePassword: false })
}

beforeEach(() => { clearTokens(); storage.clear() })

test('concurrent requests rotate once and preserve selected tenant', async () => {
  existingSession()
  let count = 0
  const fresh = token(900)
  globalThis.fetch = async () => { count++; await new Promise(resolve => setImmediate(resolve)); return response(fresh) }
  assert.deepEqual(await Promise.all([ensureAccessToken(), ensureAccessToken(), ensureAccessToken()]), [fresh, fresh, fresh])
  assert.equal(count, 1)
  assert.equal(storage.get('tenant_id'), 'selected-tenant')
})

test('late 401 reuses already rotated token instead of consuming refresh twice', async () => {
  existingSession()
  const old = storage.get('access_token')
  const fresh = token(900)
  let count = 0
  globalThis.fetch = async () => { count++; return response(fresh) }
  await ensureAccessToken()
  assert.equal(await ensureAccessToken(true, old), fresh)
  assert.equal(count, 1)
})

test('network failure retains session for retry', async () => {
  existingSession()
  globalThis.fetch = async () => { throw new TypeError('offline') }
  await assert.rejects(ensureAccessToken(), /offline/)
  assert.equal(storage.get('refresh_token'), 'initial-refresh')
})

test('rejected refresh invalidates local credentials', async () => {
  existingSession()
  globalThis.fetch = async () => Response.json({ message: 'revoked' }, { status: 401 })
  await assert.rejects(ensureAccessToken(), AuthenticationError)
  assert.equal(storage.size, 0)
})

test('logout while refresh is pending cannot resurrect login', async () => {
  existingSession()
  let finish
  globalThis.fetch = async url => url.endsWith('/refresh')
    ? new Promise(resolve => { finish = () => resolve(response(token(900))) })
    : new Response(null, { status: 204 })
  const pending = ensureAccessToken()
  await logout()
  finish()
  await assert.rejects(pending, /登录状态已变更/)
  assert.equal(storage.size, 0)
})

test('a rejected mutation cannot retry using a different administrator session', async () => {
  existingSession(900)
  let finish
  let requests = 0
  globalThis.fetch = async () => {
    requests++
    return new Promise(resolve => { finish = () => resolve(Response.json({}, { status: 401 })) })
  }
  const pending = postJson('/admin/assets/stations', { code: 'station-a' })
  await new Promise(resolve => setImmediate(resolve))
  clearTokens()
  existingSession(900)
  finish()
  await assert.rejects(pending, /账号或租户已切换/)
  assert.equal(requests, 1)
  assert.equal(storage.get('refresh_token'), 'initial-refresh')
})

test('tenant switch rejects prior response without retrying a mutation', async () => {
  existingSession(900)
  let finish
  let requests = 0
  globalThis.fetch = async () => {
    requests++
    return new Promise(resolve => { finish = () => resolve(Response.json({}, { status: 401 })) })
  }
  const pending = postJson('/admin/finance/refunds', { amountMinor: 100 }, 'refund-stable-key')
  await new Promise(resolve => setImmediate(resolve))
  storage.set('tenant_id', 'different-tenant')
  finish()
  await assert.rejects(pending, /账号或租户已切换/)
  assert.equal(requests, 1)
})

test('financial submission keys survive retries but change for new payload or tenant', () => {
  const keys = new SubmissionKeys()
  const first = keys.forRequest('refund', 'tenant-a', { amount: 100 })
  assert.equal(keys.forRequest('refund', 'tenant-a', { amount: 100 }), first)
  const changed = keys.forRequest('refund', 'tenant-a', { amount: 200 })
  assert.notEqual(changed, first)
  const otherTenant = keys.forRequest('refund', 'tenant-b', { amount: 200 })
  assert.notEqual(otherTenant, changed)
  keys.completed('refund')
  assert.notEqual(keys.forRequest('refund', 'tenant-b', { amount: 200 }), otherTenant)
})

test('statement import rejects fractional amounts and preserves real provider evidence', () => {
  assert.deepEqual(parseReconciliationRows('[{"merchantOrderNo":"order-1","providerTransactionNo":"provider-1","amountMinor":120}]'),
    [{ merchantOrderNo: 'order-1', providerTransactionNo: 'provider-1', amountMinor: 120 }])
  assert.throws(() => parseReconciliationRows('[{"merchantOrderNo":"order-1","providerTransactionNo":"provider-1","amountMinor":1.25}]'), /整数分金额/)
  assert.throws(() => parseReconciliationRows('[{"amountMinor":100}]'), /缺少有效/)
})

test('operator stop requires explicit confirmation and never claims physical completion', () => {
  const order = { id: 'order-1', orderNo: 'charging-1', status: 'CHARGING' }
  let confirmation
  const payload = prepareOperatorStop(order, ' 用户请求 ', message => { confirmation = message; return true })
  assert.deepEqual(payload, { reason: '用户请求' })
  assert.match(confirmation, /charging-1/)
  assert.match(confirmation, /不能视为已经断电/)
  assert.equal(order.status, 'CHARGING')
  assert.equal(prepareOperatorStop(order, '安全原因', () => false), null)
})

test('operator stop prevents duplicate stop-pending or completed submissions', () => {
  for (const status of ['STOP_PENDING', 'COMPLETED', 'CANCELLED', 'START_PENDING']) {
    assert.equal(prepareOperatorStop({ id: 'order-1', orderNo: 'charging-1', status }, '安全原因', () => { throw new Error('Must not confirm') }), null)
  }
})

test('operator stop reason is required and limited for audited safety control', () => {
  const order = { id: 'order-1', orderNo: 'charging-1', status: 'CHARGING' }
  assert.throws(() => prepareOperatorStop(order, '   ', () => true), /停止原因/)
  assert.throws(() => prepareOperatorStop(order, 'x'.repeat(501), () => true), /停止原因/)
})

test('strong ASCII passwords of 12 through 31 characters meet the BCrypt policy', () => {
  for (let length = 12; length <= 31; length++) {
    assert.equal(Object.values(evaluatePassword('Aa1!' + 'x'.repeat(length - 4))).every(Boolean), true)
  }
  assert.equal(evaluatePassword('Aa1!' + 'x'.repeat(7)).length, false)
})

test('Unicode password length is checked using UTF-8 bytes rather than character count', () => {
  const withinLimit = 'Aa1!' + '汉'.repeat(22)
  const overLimit = 'Aa1!' + '汉'.repeat(23)
  assert.equal(withinLimit.length, 26)
  assert.equal(overLimit.length, 27)
  assert.equal(Object.values(evaluatePassword(withinLimit)).every(Boolean), true)
  assert.equal(evaluatePassword(overLimit).length, false)
  assert.equal(evaluatePassword('Aa1!' + 'x'.repeat(68)).length, true)
  assert.equal(evaluatePassword('Aa1!' + 'x'.repeat(69)).length, false)
})
