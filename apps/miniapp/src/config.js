function environmentVersion() {
  try {
    return wx.getAccountInfoSync().miniProgram.envVersion || 'develop'
  } catch {
    return 'develop'
  }
}

const profiles = {
  develop: {
    apiBase: 'http://127.0.0.1:8088/api/v1',
    tenantCode: 'pilot',
    notificationTemplateIds: []
  },
  trial: {
    apiBase: 'https://api.example.invalid/api/v1',
    tenantCode: 'pilot',
    notificationTemplateIds: []
  },
  release: {
    apiBase: 'https://api.example.invalid/api/v1',
    tenantCode: 'pilot',
    notificationTemplateIds: []
  }
}

module.exports = profiles[environmentVersion()]
