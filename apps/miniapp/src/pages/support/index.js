const { request } = require('../../utils/api')

Page({
  data: { tickets: [] },
  async onShow() {
    if (!wx.getStorageSync('access_token')) return wx.showToast({ title: '请先登录', icon: 'none' })
    try { this.setData({ tickets: await request('/customer/support/tickets') }) }
    catch (error) { wx.showToast({ title: error?.message || '加载失败', icon: 'none' }) }
  },
  async submit(event) {
    try {
      await request('/customer/support/tickets', 'POST', event.detail.value)
      wx.showToast({ title: '已提交', icon: 'success' })
      this.onShow()
    } catch (error) { wx.showToast({ title: error?.message || '提交失败', icon: 'none' }) }
  }
})
