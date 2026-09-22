const platform = require('../../platform/wechat')

Page({
  data: { scanning: false },
  async scanToCharge() {
    if (this.data.scanning) return
    this.setData({ scanning: true })
    try {
      const code = await platform.scanCode()
      await wx.navigateTo({ url: `/pages/charge/index?code=${encodeURIComponent(code)}` })
    } catch (error) {
      if (!String(error?.errMsg || '').includes('cancel')) {
        wx.showToast({ title: error?.message || '扫码失败', icon: 'none' })
      }
    } finally {
      this.setData({ scanning: false })
    }
  }
})
