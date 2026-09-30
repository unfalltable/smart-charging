const KEYS = Object.freeze({
  accessToken: 'access_token',
  refreshToken: 'refresh_token',
  tenantId: 'tenant_id',
  customerId: 'customer_id'
})
let version = 0

function getSession() {
  return {
    accessToken: wx.getStorageSync(KEYS.accessToken),
    refreshToken: wx.getStorageSync(KEYS.refreshToken),
    tenantId: wx.getStorageSync(KEYS.tenantId),
    customerId: wx.getStorageSync(KEYS.customerId)
  }
}

function isLoggedIn() {
  const session = getSession()
  return Boolean(session.accessToken && session.refreshToken && session.tenantId && session.customerId)
}

function saveSession(session) {
  if (!session?.accessToken || !session?.refreshToken || !session?.tenantId || !session?.customerId) {
    throw new Error('登录响应不完整，请联系平台客服')
  }
  wx.setStorageSync(KEYS.accessToken, session.accessToken)
  wx.setStorageSync(KEYS.refreshToken, session.refreshToken)
  wx.setStorageSync(KEYS.tenantId, session.tenantId)
  wx.setStorageSync(KEYS.customerId, session.customerId)
  version += 1
}

function clearSession() {
  version += 1
  Object.values(KEYS).forEach((key) => wx.removeStorageSync(key))
}

function goToLogin() {
  wx.switchTab({ url: '/pages/account/index' })
}

module.exports = { KEYS, getSession, isLoggedIn, saveSession, clearSession, goToLogin, getVersion: () => version }
