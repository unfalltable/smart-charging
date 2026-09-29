const platform = require('../../platform/wechat')
const { showError } = require('../../utils/presenter')

Page({
  data: { scanning: false },
  onLoad(options) {
    const incoming = options?.token || options?.code || options?.scene
    if (!incoming) return
    try {
      const token = platform.extractChargingToken(decodeURIComponent(incoming))
      wx.navigateTo({ url: `/pages/charge/index?code=${encodeURIComponent(token)}` })
    } catch (error) { showError(error, '充电二维码无效') }
  },
  async scanToCharge() {
    if (this.data.scanning) return
    this.setData({ scanning: true })
    try {
      const code = await platform.scanCode()
      wx.navigateTo({ url: `/pages/charge/index?code=${encodeURIComponent(code)}` })
    } catch (error) {
      if (!String(error?.errMsg || '').includes('cancel')) {
        showError(error, '扫码失败')
      }
    } finally {
      this.setData({ scanning: false })
    }
  }
})
