const { request } = require('../../utils/api')
const sessionStore = require('../../utils/session')
const { showError, confirm } = require('../../utils/presenter')

Page({
  data: { agreements: [], loading: false, errorMessage: '', acceptingId: '' },
  async onShow() {
    if (!sessionStore.isLoggedIn()) {
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
    this.setData({ loading: true, errorMessage: '' })
    try { this.setData({ agreements: await request('/customer/agreements') }) }
    catch (error) { this.setData({ errorMessage: error?.message || '协议加载失败' }) }
    finally { this.setData({ loading: false }) }
  },
  open(event) {
    const url = event.currentTarget.dataset.url
    if (url) wx.navigateTo({ url: `/pages/webview/index?url=${encodeURIComponent(url)}` })
  },
  async accept(event) {
    const id = event.currentTarget.dataset.id
    if (this.data.acceptingId) return
    if (!(await confirm('请确认你已阅读协议全文，并同意协议中的全部内容。', '同意协议'))) return
    this.setData({ acceptingId: id })
    try {
      await request(`/customer/agreements/${id}/accept`, { method: 'POST', data: { source: 'WECHAT' } })
      wx.showToast({ title: '已同意', icon: 'success' })
      await this.loadAgreements()
    } catch (error) { showError(error, '操作失败') }
    finally { this.setData({ acceptingId: '' }) }
  }
})
