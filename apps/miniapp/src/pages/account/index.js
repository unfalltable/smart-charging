const platform = require('../../platform/wechat')
const { request } = require('../../utils/api')

Page({
  data: { loggingIn: false, loggedIn: false },
  onShow() { this.setData({ loggedIn: Boolean(wx.getStorageSync('access_token')) }) },
  async login() {
    if (this.data.loggingIn) return
    this.setData({ loggingIn: true })
    try {
      const code = await platform.loginCode()
      const session = await request('/auth/miniapp/login', 'POST', {
        provider: platform.provider, code, tenantCode: getApp().globalData.tenantCode
      })
      wx.setStorageSync('access_token', session.accessToken)
      if (session.refreshToken) wx.setStorageSync('refresh_token', session.refreshToken)
      wx.setStorageSync('tenant_id', session.tenantId)
      wx.setStorageSync('customer_id', session.customerId)
      this.setData({ loggedIn: true })
      wx.showToast({ title: '登录成功', icon: 'success' })
    } catch (error) {
      wx.showToast({ title: error?.message || '登录失败', icon: 'none' })
    } finally {
      this.setData({ loggingIn: false })
    }
  },
  openWallet() { wx.navigateTo({ url: '/pages/wallet/index' }) },
  openInvoices() { wx.navigateTo({ url: '/pages/invoices/index' }) },
  openAgreements() { wx.navigateTo({ url: '/pages/agreements/index' }) },
  openSupport() { wx.navigateTo({ url: '/pages/support/index' }) },
  async logout() {
    const refreshToken = wx.getStorageSync('refresh_token')
    const tenantId = wx.getStorageSync('tenant_id')
    if (refreshToken && tenantId) {
      try { await request('/auth/miniapp/logout', 'POST', { refreshToken, tenantId }) } catch { }
    }
    wx.removeStorageSync('access_token')
    wx.removeStorageSync('refresh_token')
    wx.removeStorageSync('tenant_id')
    wx.removeStorageSync('customer_id')
    this.setData({ loggedIn: false })
  }
})
