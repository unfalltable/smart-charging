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
  async onShow() {
    if (this.data.code && !this.data.loading && !this.data.starting) await this.loadConnector()
  },
  async loadConnector() {
    if (!this.data.code) {
      this.setData({ loading: false, errorMessage: '二维码缺少充电位信息' })
      return
    }
    if (this._loadingConnector) return
    this._loadingConnector = true
    this.setData({ loading: true, available: false, errorMessage: '', connector: null })
    try {
      const connector = await request(`/public/scan/${encodeURIComponent(this.data.code)}`, { auth: false })
      if (connector.tenantCode !== getApp().globalData.tenantCode) {
        throw new Error('该充电位属于其他运营服务，请使用二维码指定的小程序')
      }
      this.setData({ connector, available: connector.status === 'AVAILABLE' })
    } catch (error) {
      this.setData({ errorMessage: error?.message || '二维码无效或已过期' })
    } finally {
      this._loadingConnector = false
      this.setData({ loading: false })
    }
  },
  async startCharging() {
    if (!this.data.available || this.data.starting) return
    this.setData({ starting: true })
    try {
      await login()
      if (require('../../utils/session').getSession().tenantId !== this.data.connector.tenantId) {
        throw new Error('登录账号与充电位运营方不一致，请退出后重新登录')
      }
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
      this.setData({ available: false })
      wx.switchTab({ url: '/pages/orders/index' })
    } catch (error) {
      showError(error, '启动失败')
    } finally {
      this.setData({ starting: false })
    }
  }
})
