const { request } = require('../../utils/api')
const sessionStore = require('../../utils/session')
const { statusLabel, yuan } = require('../../utils/presenter')

Page({
  data: { loading: true, errorMessage: '', wallet: null, payments: [], refunds: [] },
  async onShow() {
    if (!sessionStore.isLoggedIn()) {
      this.setData({ loading: false, wallet: null, payments: [], refunds: [] })
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
    const identity = sessionStore.getSession()
    const identityKey = `${identity.tenantId}/${identity.customerId}`
    if (this._identityKey !== identityKey) {
      this._identityKey = identityKey
      this.setData({ wallet: null, payments: [], refunds: [], errorMessage: '' })
    }
    if (this._loading) return
    this._loading = true
    this.setData({ loading: true, errorMessage: '' })
    try {
      const [wallet, payments, refunds] = await Promise.all([
        request('/customer/finance/wallet'), request('/customer/finance/payments'), request('/customer/finance/refunds')
      ])
      if (identity.customerId !== sessionStore.getSession().customerId) return
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
          statusText: item.status === 'CREATED' ? '待支付' : statusLabel(item.status),
          channelText: item.channel === 'WECHAT' ? '微信支付' : item.channel
        })),
        refunds: refunds.map(item => ({ ...item, amountYuan: yuan(item.amountMinor), statusText: item.status === 'CREATED' ? '待提交退款' : statusLabel(item.status) }))
      })
    } catch (error) {
      if (identityKey === `${sessionStore.getSession().tenantId}/${sessionStore.getSession().customerId}`) this.setData({ errorMessage: error?.message || '加载失败' })
    } finally {
      this._loading = false; this.setData({ loading: false })
      if (sessionStore.isLoggedIn() && identityKey !== `${sessionStore.getSession().tenantId}/${sessionStore.getSession().customerId}`) await this.loadData()
    }
  }
})
