function environmentVersion() {
  try {
    return wx.getAccountInfoSync().miniProgram.envVersion || 'develop'
  } catch {
    return 'develop'
  }
}

const profiles = {
  develop: {
    apiBase: '',
    tenantCode: '',
    notificationTemplateIds: []
  },
  trial: {
    apiBase: '',
    tenantCode: '',
    notificationTemplateIds: []
  },
  release: {
    apiBase: '',
    tenantCode: '',
    notificationTemplateIds: []
  }
}

const selected = profiles[environmentVersion()]
if (!selected.apiBase || !selected.tenantCode) {
  throw new Error('小程序部署配置缺失：必须填写真实 API 地址和租户编码')
}
if (!/^https:\/\//.test(selected.apiBase)) {
  throw new Error('小程序 API 必须使用 HTTPS')
}

module.exports = selected
