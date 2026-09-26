function environmentVersion() {
  try {
    return wx.getAccountInfoSync().miniProgram.envVersion || 'develop'
  } catch {
    return 'develop'
  }
}

const profiles = require('./deployment.config')

const selected = profiles[environmentVersion()]
if (!selected || !selected.apiBase || !selected.tenantCode) {
  throw new Error('小程序部署配置缺失：请运行 config-manager.cmd wizard 配置真实 API 地址和租户编码')
}
if (!/^https:\/\//.test(selected.apiBase)) {
  throw new Error('小程序 API 必须使用 HTTPS')
}

module.exports = selected
