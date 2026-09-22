const { request } = require('../../utils/api')

Page({
  data: { agreements: [], loading: false },
  async onShow() {
    if (!wx.getStorageSync('access_token')) return wx.showToast({ title: '请先登录', icon: 'none' })
    this.setData({ loading: true })
    try { this.setData({ agreements: await request('/customer/agreements') }) }
    catch (error) { wx.showToast({ title: error?.message || '协议加载失败', icon: 'none' }) }
    finally { this.setData({ loading: false }) }
  },
  open(event) {
    const url = event.currentTarget.dataset.url
    if (url) wx.navigateTo({ url: `/pages/webview/index?url=${encodeURIComponent(url)}` })
  },
  async accept(event) {
    try {
      await request(`/customer/agreements/${event.currentTarget.dataset.id}/accept`, 'POST', { source: 'WECHAT' })
      wx.showToast({ title: '已同意', icon: 'success' })
      this.onShow()
    } catch (error) { wx.showToast({ title: error?.message || '操作失败', icon: 'none' }) }
  }
})
