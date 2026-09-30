const sessionStore = require('./session')

const DEFAULT_TIMEOUT = 15000
let refreshInFlight = null

const LOCALIZED_MESSAGES = Object.freeze({
  'Tenant is unavailable': '当前运营服务暂不可用',
  'Mini-program identity provider is not configured': '微信登录尚未配置完成',
  'WeChat authorization code was rejected': '微信登录凭证已失效，请重试',
  'WeChat did not return a user identity': '微信未返回有效用户身份',
  'WeChat returned an invalid identity response': '微信登录服务响应异常，请稍后重试',
  'Customer account is not active': '消费者账号已停用或注销',
  'Refresh token reuse was detected; the session has been revoked': '检测到异常登录凭证，已退出所有设备',
  'Refresh token is invalid or expired': '登录已过期，请重新登录',
  'Unsupported charging QR code': '不支持的充电二维码',
  'Malformed charging QR code': '充电二维码格式错误',
  'Charging QR signature is invalid': '充电二维码无效或已过期',
  'Charging connector is unavailable': '该充电位暂不可用',
  'Customer does not exist or is inactive': '消费者账号不可用，请重新登录',
  'Required service agreements must be accepted before charging': '请先阅读并同意服务协议',
  'Connector is not available': '该充电位当前不可用',
  'Connector has no active tariff': '该充电位尚未配置有效费率',
  'Only a charging order can be stopped': '当前订单不能执行停止操作',
  'Order can only be paid after charging completes': '充电结束后才能支付',
  'Order has no outstanding balance': '该订单没有待支付金额',
  'Another payment channel is already processing for this order': '该订单已有支付正在处理中',
  'Customer has no WeChat payment identity': '当前账号无法发起微信支付，请重新登录',
  'Payment amount exceeds channel limit': '支付金额超过渠道限制',
  'Only a fully paid order can be invoiced': '仅已付清订单可以申请发票',
  'An invoice has already been requested for this order': '该订单已申请过发票',
  'Completed order does not exist': '未找到可开票的已完成订单',
  'Agreement document is unavailable': '协议已失效，请刷新后重试',
  'Stop the active charging order before closing the account': '请先停止进行中的充电再注销账号',
  'Pay all outstanding charging orders before closing the account': '请先付清全部充电订单再注销账号',
  'Withdraw or settle the wallet balance before closing the account': '账号仍有余额或冻结资金，请先联系客服处理'
})

class ApiError extends Error {
  constructor(message, statusCode = 0, code = 'REQUEST_FAILED', violations = []) {
    super(message)
    this.name = 'ApiError'
    this.statusCode = statusCode
    this.code = code
    this.violations = violations
  }
}

function appConfig() {
  return getApp().globalData
}

function errorFromResponse(response) {
  const body = response?.data || {}
  const fieldMessage = Array.isArray(body.violations) && body.violations.length
    ? body.violations.map((item) => item.message).filter(Boolean).join('；')
    : ''
  const serverMessage = fieldMessage || body.message
  return new ApiError(
    LOCALIZED_MESSAGES[serverMessage] || serverMessage || `服务请求失败 (${response?.statusCode || 0})`,
    response?.statusCode || 0,
    body.code || 'REQUEST_FAILED',
    body.violations || []
  )
}

function networkError(error) {
  const detail = String(error?.errMsg || '')
  if (detail.includes('timeout')) return new ApiError('网络请求超时，请检查网络后重试', 0, 'NETWORK_TIMEOUT')
  return new ApiError('暂时无法连接服务，请检查网络后重试', 0, 'NETWORK_UNAVAILABLE')
}

function rawRequest(path, options = {}) {
  const { method = 'GET', data, headers = {}, timeout = DEFAULT_TIMEOUT } = options
  return new Promise((resolve, reject) => {
    wx.request({
      url: `${appConfig().apiBase}${path}`,
      method,
      data,
      timeout,
      header: { Accept: 'application/json', 'Content-Type': 'application/json', ...headers },
      success: resolve,
      fail: (error) => reject(networkError(error))
    })
  })
}

function refreshSession(rejectedToken) {
  const session = sessionStore.getSession()
  if (rejectedToken && session.accessToken && session.accessToken !== rejectedToken) return Promise.resolve(session)
  if (refreshInFlight) return refreshInFlight
  if (!session.refreshToken || !session.tenantId) {
    sessionStore.clearSession()
    return Promise.reject(new ApiError('登录已过期，请重新登录', 401, 'LOGIN_REQUIRED'))
  }
  const version = sessionStore.getVersion()
  refreshInFlight = rawRequest('/auth/miniapp/refresh', {
    method: 'POST',
    data: { refreshToken: session.refreshToken, tenantId: session.tenantId }
  }).then((response) => {
    if (response.statusCode < 200 || response.statusCode >= 300) throw errorFromResponse(response)
    if (version !== sessionStore.getVersion()) throw new ApiError('登录状态已变更，请重新登录', 401, 'LOGIN_REQUIRED')
    sessionStore.saveSession(response.data)
    return response.data
  }).catch((error) => {
    if (error.statusCode === 401 && version === sessionStore.getVersion()) sessionStore.clearSession()
    throw error.statusCode === 401
      ? new ApiError('登录已过期，请重新登录', 401, 'LOGIN_REQUIRED')
      : error
  }).finally(() => { refreshInFlight = null })
  return refreshInFlight
}

async function request(path, options = {}) {
  const { auth = true, retry = true, method = 'GET', data, idempotencyKey } = options
  const session = sessionStore.getSession()
  if (auth && !session.accessToken) {
    throw new ApiError('请先登录', 401, 'LOGIN_REQUIRED')
  }
  const headers = {
    ...(auth ? { Authorization: `Bearer ${session.accessToken}`, 'X-Tenant-Id': session.tenantId } : {}),
    ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {})
  }
  const response = await rawRequest(path, { method, data, headers })
  if (auth) {
    const current = sessionStore.getSession()
    if (current.tenantId !== session.tenantId || current.customerId !== session.customerId) {
      throw new ApiError('登录账号已变更，请重新操作', 401, current.accessToken ? 'SESSION_CHANGED' : 'LOGIN_REQUIRED')
    }
  }
  if (response.statusCode >= 200 && response.statusCode < 300) return response.data
  if (auth && retry && response.statusCode === 401) {
    await refreshSession(session.accessToken)
    return request(path, { ...options, retry: false })
  }
  if (auth && response.statusCode === 401) {
    if (session.accessToken === sessionStore.getSession().accessToken) sessionStore.clearSession()
    throw new ApiError('登录已过期，请重新登录', 401, 'LOGIN_REQUIRED')
  }
  throw errorFromResponse(response)
}

module.exports = { ApiError, request, refreshSession }
