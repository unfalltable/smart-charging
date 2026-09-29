const { request } = require('../../utils/api')
const { login } = require('../../utils/auth')
const { showError } = require('../../utils/presenter')

Page({
  data: {
    loading: true,
    starting: false,
    connector: null,
    available: false,
    errorMessage: '',
    code: '',
    idempotencyKey: ''
  },
  async onLoad({ code }) {
    this.setData({
      code: code || '',
      idempotencyKey: `start-${Date.now()}-${Math.random().toString(36).slice(2)}`
    })
    await this.loadConnector()
  },
  async loadConnector() {
    if (!this.data.code) {
      this.setData({ loading: false, errorMessage: '二维码缺少充电位信息' })
      return
    }
    this.setData({ loading: true, errorMessage: '', connector: null })
    try {
      const connector = await request(`/public/scan/${encodeURIComponent(this.data.code)}`, { auth: false })
      this.setData({ connector, available: connector.status === 'AVAILABLE' })
    } catch (error) {
      this.setData({ errorMessage: error?.message || '二维码无效或已过期' })
    } finally {
      this.setData({ loading: false })
    }
  },
  async startCharging() {
    if (!this.data.available || this.data.starting) return
    this.setData({ starting: true })
    try {
      await login()
      const agreements = await request('/customer/agreements')
      if (agreements.some((item) => !item.accepted)) {
        wx.showModal({
          title: '请先确认服务协议',
          content: '开始充电前，需要阅读并同意当前有效的服务协议和隐私政策。',
          confirmText: '去查看',
          confirmColor: '#087d69',
          success: ({ confirm }) => { if (confirm) wx.navigateTo({ url: '/pages/agreements/index' }) }
        })
        return
      }
      const connector = this.data.connector
      await request('/charging/orders', {
        method: 'POST',
        data: { connectorId: connector.id },
        idempotencyKey: this.data.idempotencyKey
      })
      wx.showToast({ title: '启动指令已发送', icon: 'success' })
      setTimeout(() => wx.switchTab({ url: '/pages/orders/index' }), 800)
    } catch (error) {
      showError(error, '启动失败')
    } finally {
      this.setData({ starting: false })
    }
  }
})
