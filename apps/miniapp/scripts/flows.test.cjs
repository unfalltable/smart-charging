const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const sourceRoot = path.resolve(__dirname, '../src')
let storage
let session
let api

beforeEach(() => {
  for (const module of ['utils/api.js', 'utils/session.js', 'utils/auth.js']) delete require.cache[require.resolve(path.join(sourceRoot, module))]
  storage = new Map()
  global.wx = {
    getStorageSync: key => storage.get(key) || '',
    setStorageSync: (key, value) => storage.set(key, value),
    removeStorageSync: key => storage.delete(key)
  }
  global.getApp = () => ({ globalData: { apiBase: 'https://service.example/api/v1', tenantCode: 'operator-a', notificationTemplateIds: [] } })
  session = require(path.join(sourceRoot, 'utils/session.js'))
  api = require(path.join(sourceRoot, 'utils/api.js'))
  session.saveSession({ accessToken: 'old', refreshToken: 'refresh', tenantId: 'tenant-a', customerId: 'customer-a' })
})

const freshSession = () => ({ accessToken: 'fresh', refreshToken: 'next-refresh', tenantId: 'tenant-a', customerId: 'customer-a' })
const response = (data, statusCode = 200) => ({ data, statusCode })
const tick = () => new Promise(resolve => setImmediate(resolve))

test('parallel expired requests share one refresh request', async () => {
  let refreshes = 0
  wx.request = options => {
    setImmediate(() => {
      if (options.url.endsWith('/auth/miniapp/refresh')) { refreshes++; options.success(response(freshSession())) }
      else options.success(options.header.Authorization === 'Bearer fresh' ? response({ ok: true }) : response({}, 401))
    })
  }
  assert.deepEqual(await Promise.all([api.request('/one'), api.request('/two')]), [{ ok: true }, { ok: true }])
  assert.equal(refreshes, 1)
})

test('late expired response cannot rotate a fresh session twice', async () => {
  let delayed
  let refreshes = 0
  wx.request = options => {
    if (options.url.endsWith('/delayed') && options.header.Authorization === 'Bearer old') { delayed = () => options.success(response({}, 401)); return }
    if (options.url.endsWith('/refresh')) { refreshes++; options.success(response(freshSession())); return }
    options.success(options.header.Authorization === 'Bearer fresh' ? response('ok') : response({}, 401))
  }
  const pending = api.request('/delayed')
  await api.request('/fast')
  delayed()
  assert.equal(await pending, 'ok')
  assert.equal(refreshes, 1)
})

test('refresh network failure keeps credentials available for retry', async () => {
  wx.request = options => options.url.endsWith('/refresh')
    ? options.fail({ errMsg: 'request:fail timeout' })
    : options.success(response({}, 401))
  await assert.rejects(api.request('/orders'), error => error.code === 'NETWORK_TIMEOUT')
  assert.equal(session.getSession().refreshToken, 'refresh')
})

test('revoked refresh clears authentication', async () => {
  wx.request = options => options.success(response({}, 401))
  await assert.rejects(api.request('/orders'), error => error.code === 'LOGIN_REQUIRED')
  assert.equal(session.isLoggedIn(), false)
})

test('pending refresh cannot restore a logged out session', async () => {
  let finish
  wx.request = options => { finish = () => options.success(response(freshSession())) }
  const pending = api.refreshSession()
  session.clearSession()
  finish()
  await assert.rejects(pending, error => error.code === 'LOGIN_REQUIRED')
  assert.equal(session.isLoggedIn(), false)
})

test('an old request cannot be retried under another customer account', async () => {
  let complete
  let count = 0
  wx.request = options => { count++; complete = () => options.success(response({}, 401)) }
  const pending = api.request('/charging/orders', { method: 'POST', data: { connectorId: 'connector-a' } })
  session.saveSession({ accessToken: 'other', refreshToken: 'other-refresh', tenantId: 'tenant-a', customerId: 'other-customer' })
  complete()
  await assert.rejects(pending, error => error.code === 'SESSION_CHANGED')
  assert.equal(count, 1)
  assert.equal(session.getSession().customerId, 'other-customer')
})

function page(file, mocks) {
  let definition
  vm.runInNewContext(fs.readFileSync(path.join(sourceRoot, file), 'utf8'), {
    Page: value => { definition = value },
    require: name => { if (!(name in mocks)) throw new Error(`Missing page dependency ${name}`); return mocks[name] },
    wx: global.wx, getApp: global.getApp, setTimeout, clearTimeout, Map, Set, Date
  }, { filename: file })
  definition.data = { ...definition.data }
  definition.setData = values => Object.assign(definition.data, values)
  return definition
}

function orderPage(request, pay = async () => {}) {
  wx.showToast = () => {}
  return page('pages/orders/index.js', {
    '../../utils/api': { request },
    '../../utils/session': session,
    '../../platform/wechat': { pay, subscribeNotifications: async () => {} },
    '../../utils/presenter': { statusLabel: value => value, yuan: value => String(value), kilowattHours: value => String(value), showError: () => {}, confirm: async () => true }
  })
}

test('already successful payment skips native payment dialog and confirms with server', async () => {
  let nativePayments = 0
  const instance = orderPage(async endpoint => endpoint === '/payments'
    ? { status: 'SUCCEEDED' } : [{ orderId: 'order-a', status: 'COMPLETED', payableAmountMinor: 100, paidAmountMinor: 100 }],
  async () => { nativePayments++ })
  await instance.payOrder({ currentTarget: { dataset: { orderId: 'order-a' } } })
  assert.equal(nativePayments, 0)
  assert.equal(instance.data.orders[0].canPay, false)
  assert.equal(instance.data.actionOrderId, '')
})

test('cancelled payment retry retains its original idempotency key', async () => {
  const keys = []
  const instance = orderPage(async (endpoint, options) => {
    if (endpoint === '/payments') { keys.push(options.idempotencyKey); return { status: 'PROCESSING', clientParameters: {} } }
    return [{ orderId: 'order-a', status: 'COMPLETED', payableAmountMinor: 100, paidAmountMinor: 0 }]
  }, async () => { throw { errMsg: 'requestPayment:fail cancel' } })
  const event = { currentTarget: { dataset: { orderId: 'order-a' } } }
  await instance.payOrder(event)
  await instance.payOrder(event)
  assert.equal(keys.length, 2)
  assert.equal(keys[0], keys[1])
})

for (const terminalState of ['FAILED', 'CLOSED']) {
  test(`${terminalState} payment skips native payment and the next attempt uses a fresh key`, async () => {
    const keys = []
    let nativePayments = 0
    const instance = orderPage(async (endpoint, options) => {
      if (endpoint === '/payments') {
        keys.push(options.idempotencyKey)
        return { status: keys.length === 1 ? terminalState : 'PROCESSING', clientParameters: {} }
      }
      if (endpoint === '/customer/finance/payments') return [{ orderId: 'order-a', status: 'PROCESSING' }]
      return [{ orderId: 'order-a', status: 'COMPLETED', payableAmountMinor: 100, paidAmountMinor: 0 }]
    }, async () => { nativePayments++ })
    const event = { currentTarget: { dataset: { orderId: 'order-a' } } }
    await instance.payOrder(event)
    assert.equal(nativePayments, 0)
    assert.equal(instance._paymentKeys.has('order-a'), false)
    assert.equal(instance._confirmingPayments.has('order-a'), false)
    assert.equal(instance.data.orders[0].canPay, true)
    await instance.payOrder(event)
    assert.equal(nativePayments, 1)
    assert.notEqual(keys[0], keys[1])
  })
}

test('unknown payment outcome after a network failure preserves the same key for retry', async () => {
  const keys = []
  let nativePayments = 0
  const instance = orderPage(async (endpoint, options) => {
    if (endpoint === '/payments') {
      keys.push(options.idempotencyKey)
      throw new Error('network timeout: provider result is unknown')
    }
    return [{ orderId: 'order-a', status: 'COMPLETED', payableAmountMinor: 100, paidAmountMinor: 0 }]
  }, async () => { nativePayments++ })
  const event = { currentTarget: { dataset: { orderId: 'order-a' } } }
  await instance.payOrder(event)
  assert.ok(instance._paymentKeys.has('order-a'))
  await instance.payOrder(event)
  assert.equal(nativePayments, 0)
  assert.equal(keys.length, 2)
  assert.equal(keys[0], keys[1])
})

test('a rejected invoice can be resubmitted while an issued invoice remains excluded', async () => {
  const instance = page('pages/invoices/index.js', {
    '../../utils/api': { request: async endpoint => endpoint === '/customer/finance/invoices'
      ? [{ orderId: 'rejected-order', status: 'REJECTED', amountMinor: 100 }, { orderId: 'issued-order', status: 'ISSUED', amountMinor: 100 }]
      : [{ orderId: 'rejected-order', status: 'COMPLETED', paidAmountMinor: 100, payableAmountMinor: 100 }, { orderId: 'issued-order', status: 'COMPLETED', paidAmountMinor: 100, payableAmountMinor: 100 }] },
    '../../utils/session': session,
    '../../utils/presenter': { statusLabel: value => value, yuan: value => String(value), showError: () => {} }
  })
  await instance.loadData()
  assert.equal(instance.data.orders.length, 1)
  assert.equal(instance.data.orders[0].orderId, 'rejected-order')
  assert.equal(instance.data.invoices.length, 2)
})

test('a completed refunded order never asks the customer to pay the refund again', async () => {
  const instance = orderPage(async () => [{ orderId: 'order-a', status: 'COMPLETED', payableAmountMinor: 100, paidAmountMinor: 0, refundedAmountMinor: 100 }])
  await instance.loadOrders()
  assert.equal(instance.data.orders[0].canPay, false)
  assert.equal(instance.data.orders[0].outstandingYuan, '0')
})

test('active charging polls only while order page is visible', async () => {
  const instance = orderPage(async () => [{ orderId: 'order-a', status: 'CHARGING', payableAmountMinor: 0, paidAmountMinor: 0 }])
  await instance.onShow()
  assert.ok(instance._pollTimer)
  instance.onHide()
  assert.equal(instance._visible, false)
  assert.equal(instance._pollTimer._destroyed, true)
  await tick()
})

test('switching customer clears old order cards before the next response', async () => {
  let release
  let secondAccount = false
  const instance = orderPage(async () => secondAccount
    ? new Promise(resolve => { release = () => resolve([]) })
    : [{ orderId: 'private-old-order', status: 'COMPLETED', payableAmountMinor: 100, paidAmountMinor: 100 }])
  await instance.onShow()
  assert.equal(instance.data.orders.length, 1)
  secondAccount = true
  session.saveSession({ accessToken: 'other', refreshToken: 'next', tenantId: 'tenant-a', customerId: 'customer-b' })
  const pending = instance.onShow()
  assert.equal(instance.data.orders.length, 0)
  release()
  await pending
  instance.onHide()
})

test('wallet failure after customer switch never shows prior account funds', async () => {
  const instance = page('pages/wallet/index.js', {
    '../../utils/api': { request: async () => { throw new Error('offline') } },
    '../../utils/session': session,
    '../../utils/presenter': { statusLabel: value => value, yuan: value => String(value) }
  })
  instance._identityKey = 'tenant-a/customer-old'
  instance.setData({ wallet: { balanceMinor: 1000 }, payments: [{ id: 'old-payment' }], refunds: [{ id: 'old-refund' }] })
  await instance.loadData()
  assert.equal(instance.data.wallet, null)
  assert.equal(instance.data.payments.length, 0)
  assert.equal(instance.data.refunds.length, 0)
  assert.equal(instance.data.errorMessage, 'offline')
})

test('charging scan prevents starting a connector in a different operator service', async () => {
  const instance = page('pages/charge/index.js', {
    '../../utils/api': { request: async () => ({ id: 'connector-a', tenantCode: 'other-operator', status: 'AVAILABLE' }) },
    '../../utils/auth': { login: async () => {} },
    '../../utils/presenter': { showError: () => {} }
  })
  instance.setData({ code: 'signed-token' })
  await instance.loadConnector()
  assert.equal(instance.data.available, false)
  assert.match(instance.data.errorMessage, /其他运营服务/)
})
