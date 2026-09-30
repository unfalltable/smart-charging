const { request } = require('../../utils/api')
const sessionStore = require('../../utils/session')
const { showError, confirm } = require('../../utils/presenter')

Page({
  data: { agreements: [], loading: false, errorMessage: '', acceptingId: '' },
  async onShow() {
    if (!sessionStore.isLoggedIn()) {
      this.setData({ loading: false, agreements: [] })
      wx.showToast({ title: '请先登录', icon: 'none' })
      setTimeout(() => sessionStore.goToLogin(), 500)
      return
    }
    await this.loadAgreements()
  },
  async onPullDownRefresh() {
    try { await this.loadAgreements() } finally { wx.stopPullDownRefresh() }
  },
  async loadAgreements() {
    const identity = sessionStore.getSession()
    const identityKey = `${identity.tenantId}/${identity.customerId}`
    if (this._identityKey !== identityKey) {
      this._identityKey = identityKey
      this.setData({ agreements: [], acceptingId: '', errorMessage: '' })
    }
    if (this._loading) return
    this._loading = true
    this.setData({ loading: true, errorMessage: '' })
    try {
      const agreements = await request('/customer/agreements')
      if (identityKey !== `${sessionStore.getSession().tenantId}/${sessionStore.getSession().customerId}`) return
      this.setData({ agreements })
    }
    catch (error) {
      if (identityKey === `${sessionStore.getSession().tenantId}/${sessionStore.getSession().customerId}`) this.setData({ errorMessage: error?.message || '协议加载失败' })
    } finally {
      this._loading = false; this.setData({ loading: false })
      if (sessionStore.isLoggedIn() && identityKey !== `${sessionStore.getSession().tenantId}/${sessionStore.getSession().customerId}`) await this.loadAgreements()
    }
  },
  open(event) {
    const url = event.currentTarget.dataset.url
    if (url) wx.navigateTo({ url: `/pages/webview/index?url=${encodeURIComponent(url)}` })
  },
  async accept(event) {
    const id = event.currentTarget.dataset.id
    if (this.data.acceptingId) return
    this.setData({ acceptingId: id })
    try {
      if (!(await confirm('请确认你已阅读协议全文，并同意协议中的全部内容。', '同意协议'))) return
      await request(`/customer/agreements/${id}/accept`, { method: 'POST', data: { source: 'WECHAT' } })
      wx.showToast({ title: '已同意', icon: 'success' })
      await this.loadAgreements()
    } catch (error) { showError(error, '操作失败') }
    finally { this.setData({ acceptingId: '' }) }
  }
})
