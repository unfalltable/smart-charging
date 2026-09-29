const { request } = require('../../utils/api')
const { login } = require('../../utils/auth')
const sessionStore = require('../../utils/session')
const { showError, confirm } = require('../../utils/presenter')

Page({
  data: { loggingIn: false, loggedIn: false },
  onShow() { this.setData({ loggedIn: sessionStore.isLoggedIn() }) },
  async login() {
    if (this.data.loggingIn) return
    this.setData({ loggingIn: true })
    try {
      await login()
      this.setData({ loggedIn: true })
      wx.showToast({ title: '登录成功', icon: 'success' })
    } catch (error) {
      showError(error, '登录失败')
    } finally {
      this.setData({ loggingIn: false })
    }
  },
  openWallet() { wx.navigateTo({ url: '/pages/wallet/index' }) },
  openInvoices() { wx.navigateTo({ url: '/pages/invoices/index' }) },
  openAgreements() { wx.navigateTo({ url: '/pages/agreements/index' }) },
  openSupport() { wx.navigateTo({ url: '/pages/support/index' }) },
  async logout() {
    if (!(await confirm('退出后需要重新微信登录才能查看订单，确定退出吗？', '退出登录'))) return
    const { refreshToken, tenantId } = sessionStore.getSession()
    if (refreshToken && tenantId) {
      try {
        await request('/auth/miniapp/logout', { method: 'POST', data: { refreshToken, tenantId } })
      } catch { }
    }
    sessionStore.clearSession()
    this.setData({ loggedIn: false })
    wx.showToast({ title: '已退出登录', icon: 'none' })
  },
  async closeAccount() {
    const accepted = await confirm(
      '注销后微信身份会与本平台解绑且所有设备退出登录。依法需要留存的订单、支付和发票凭证不会删除。确定继续吗？',
      '注销消费者账号'
    )
    if (!accepted) return
    try {
      await request('/customer/account/close', { method: 'POST' })
      sessionStore.clearSession()
      this.setData({ loggedIn: false })
      wx.showToast({ title: '账号已注销', icon: 'success' })
    } catch (error) { showError(error, '账号注销失败') }
  }
})
