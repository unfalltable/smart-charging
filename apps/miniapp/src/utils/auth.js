const platform = require('../platform/wechat')
const { request } = require('./api')
const sessionStore = require('./session')

let loginInFlight = null

function login() {
  if (sessionStore.isLoggedIn()) return Promise.resolve(sessionStore.getSession())
  if (loginInFlight) return loginInFlight
  loginInFlight = platform.loginCode()
    .then((code) => request('/auth/miniapp/login', {
      method: 'POST',
      auth: false,
      data: { provider: platform.provider, code, tenantCode: getApp().globalData.tenantCode }
    }))
    .then((session) => {
      sessionStore.saveSession(session)
      return session
    })
    .finally(() => { loginInFlight = null })
  return loginInFlight
}

module.exports = { login }
