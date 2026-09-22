const { request } = require('../../utils/api')

Page({
  data: { loading: true, wallet: null, payments: [] },
  async onShow() {
    if (!wx.getStorageSync('access_token')) return wx.showToast({ title: '请先登录', icon: 'none' })
    this.setData({ loading: true })
    try {
      const [wallet, payments] = await Promise.all([
        request('/customer/finance/wallet'), request('/customer/finance/payments')
      ])
      this.setData({
        wallet: { ...wallet, balanceYuan: (wallet.balanceMinor / 100).toFixed(2) },
        payments: payments.map(item => ({ ...item, amountYuan: (item.amountMinor / 100).toFixed(2) }))
      })
    } catch (error) {
      wx.showToast({ title: error?.message || '加载失败', icon: 'none' })
    } finally { this.setData({ loading: false }) }
  }
})
