const app = () => getApp()
let refreshInFlight = null

function refreshSession() {
  if (refreshInFlight) return refreshInFlight
  const refreshToken = wx.getStorageSync('refresh_token')
  const tenantId = wx.getStorageSync('tenant_id')
  if (!refreshToken || !tenantId) return Promise.reject(new Error('登录已过期'))
  refreshInFlight = new Promise((resolve, reject) => {
    wx.request({
      url: `${app().globalData.apiBase}/auth/miniapp/refresh`,
      method: 'POST',
      timeout: 10000,
      header: { Accept: 'application/json', 'Content-Type': 'application/json' },
      data: { refreshToken, tenantId },
      success(response) {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          wx.setStorageSync('access_token', response.data.accessToken)
          wx.setStorageSync('refresh_token', response.data.refreshToken)
          resolve(response.data)
        } else reject(new Error(response.data?.message || '登录已过期'))
      },
      fail: reject,
      complete() { refreshInFlight = null }
    })
  })
  return refreshInFlight
}

function request(path, method = 'GET', data, idempotencyKey, retried = false) {
  return new Promise((resolve, reject) => {
    const token = wx.getStorageSync('access_token')
    const tenantId = wx.getStorageSync('tenant_id')
    wx.request({
      url: `${app().globalData.apiBase}${path}`,
      method,
      data,
      timeout: 10000,
      header: {
        Accept: 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(tenantId ? { 'X-Tenant-Id': tenantId } : {}),
        ...(method === 'POST' && idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {})
      },
      success(response) {
        if (response.statusCode >= 200 && response.statusCode < 300) return resolve(response.data)
        if (response.statusCode === 401 && !retried && wx.getStorageSync('refresh_token')) {
          return refreshSession()
            .then(() => request(path, method, data, idempotencyKey, true))
            .then(resolve, reject)
        }
        reject(new Error(response.data?.message || `服务请求失败 (${response.statusCode})`))
      },
      fail: reject
    })
  })
}

module.exports = { request }
