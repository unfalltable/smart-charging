const { request } = require('../../utils/api')
const sessionStore = require('../../utils/session')
const { statusLabel, yuan } = require('../../utils/presenter')

Page({
  data: { loading: true, errorMessage: '', wallet: null, payments: [] },
  async onShow() {
    if (!sessionStore.isLoggedIn()) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      setTimeout(() => sessionStore.goToLogin(), 500)
      return
    }
    await this.loadData()
  },
  async onPullDownRefresh() {
    try { await this.loadData() } finally { wx.stopPullDownRefresh() }
  },
  async loadData() {
    this.setData({ loading: true, errorMessage: '' })
    try {
      const [wallet, payments] = await Promise.all([
        request('/customer/finance/wallet'), request('/customer/finance/payments')
      ])
      this.setData({
        wallet: {
          ...wallet,
          balanceYuan: yuan(wallet.balanceMinor),
          frozenYuan: yuan(wallet.frozenMinor),
          statusText: statusLabel(wallet.status)
        },
        payments: payments.map((item) => ({
          ...item,
          amountYuan: yuan(item.amountMinor),
          statusText: statusLabel(item.status),
          channelText: item.channel === 'WECHAT' ? '微信支付' : item.channel
        }))
      })
    } catch (error) {
      this.setData({ errorMessage: error?.message || '加载失败' })
    } finally { this.setData({ loading: false }) }
  }
})
