const { request } = require('../../utils/api')

Page({
  data: { loading: true, starting: false, connector: null, available: false, idempotencyKey: '' },
  async onLoad({ code }) {
    this.setData({ idempotencyKey: `start-${Date.now()}-${Math.random().toString(36).slice(2)}` })
    try {
      const connector = await request(`/public/scan/${encodeURIComponent(code || '')}`)
      this.setData({ connector, available: connector.status === 'AVAILABLE' })
    } catch (error) {
      wx.showToast({ title: error?.message || '二维码无效', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },
  async startCharging() {
    if (!this.data.available || this.data.starting) return
    this.setData({ starting: true })
    try {
      const connector = this.data.connector
      await request('/charging/orders', 'POST', {
        connectorId: connector.id
      }, this.data.idempotencyKey)
      wx.showToast({ title: '启动指令已发送', icon: 'success' })
      setTimeout(() => wx.switchTab({ url: '/pages/orders/index' }), 800)
    } catch (error) {
      wx.showToast({ title: error?.message || '启动失败', icon: 'none' })
    } finally {
      this.setData({ starting: false })
    }
  }
})
