function loginCode() {
  return new Promise((resolve, reject) => wx.login({
    success: ({ code }) => code ? resolve(code) : reject(new Error('登录凭证为空')),
    fail: reject
  }))
}

function scanCode() {
  return new Promise((resolve, reject) => wx.scanCode({
    scanType: ['qrCode'],
    success: ({ result }) => resolve(result),
    fail: reject
  }))
}

function pay(payment) {
  return new Promise((resolve, reject) => wx.requestPayment({ ...payment, success: resolve, fail: reject }))
}

function subscribeNotifications(templateIds) {
  if (!Array.isArray(templateIds) || templateIds.length === 0) return Promise.resolve({})
  return new Promise((resolve) => wx.requestSubscribeMessage({
    tmplIds: templateIds,
    success: resolve,
    fail: () => resolve({})
  }))
}

module.exports = { provider: 'WECHAT', loginCode, scanCode, pay, subscribeNotifications }
