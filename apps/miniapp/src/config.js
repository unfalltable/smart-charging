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
  throw new Error('小程序部署配置缺失：请编辑根目录 .env，再运行 config-manager.cmd export-miniapp')
}
if (!/^https:\/\//i.test(selected.apiBase)) {
  throw new Error('小程序 API 必须使用 HTTPS')
}
if (!/^https:\/\/[^/]+\/api\/v1\/?$/i.test(selected.apiBase)) {
  throw new Error('小程序 API 地址必须是 HTTPS 且以 /api/v1 结尾')
}
if (!/^[a-z0-9][a-z0-9-]{1,62}$/.test(selected.tenantCode)) {
  throw new Error('小程序租户编码格式无效')
}

module.exports = Object.freeze({
  ...selected,
  apiBase: selected.apiBase.replace(/\/+$/, ''),
  notificationTemplateIds: Array.isArray(selected.notificationTemplateIds)
    ? selected.notificationTemplateIds.filter(Boolean)
    : []
})
