function loginCode() {
  return new Promise((resolve, reject) => wx.login({
    success: ({ code }) => code ? resolve(code) : reject(new Error('登录凭证为空')),
    fail: reject
  }))
}

function scanCode() {
  return new Promise((resolve, reject) => wx.scanCode({
    scanType: ['qrCode'],
    success: ({ result }) => {
      try { resolve(extractChargingToken(result)) } catch (error) { reject(error) }
    },
    fail: reject
  }))
}

function extractChargingToken(value) {
  const raw = String(value || '').trim()
  if (!raw || raw.length > 2048) throw new Error('二维码内容无效')
  let token = raw
  if (/^https:\/\//i.test(raw)) {
    const query = raw.split('?')[1] || ''
    const values = query.split('&').filter(Boolean).reduce((result, pair) => {
      const [key, ...parts] = pair.split('=')
      result[decodeURIComponent(key)] = decodeURIComponent(parts.join('='))
      return result
    }, {})
    token = values.token || values.code || raw.split('?')[0].split('/').filter(Boolean).pop() || ''
  }
  if (!/^[A-Za-z0-9._~-]{8,1024}$/.test(token)) throw new Error('不是有效的充电桩二维码')
  return token
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

module.exports = { provider: 'WECHAT', loginCode, scanCode, pay, subscribeNotifications, extractChargingToken }
