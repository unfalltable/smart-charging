const { request } = require('../../utils/api')
const sessionStore = require('../../utils/session')
const { statusLabel, showError } = require('../../utils/presenter')

Page({
  data: { loading: true, submitting: false, errorMessage: '', title: '', description: '', tickets: [] },
  async onShow() {
    if (!sessionStore.isLoggedIn()) {
      wx.showToast({ title: '请先登录', icon: 'none' })
      setTimeout(() => sessionStore.goToLogin(), 500)
      return
    }
    await this.loadTickets()
  },
  async onPullDownRefresh() {
    try { await this.loadTickets() } finally { wx.stopPullDownRefresh() }
  },
  async loadTickets() {
    this.setData({ loading: true, errorMessage: '' })
    try {
      const tickets = await request('/customer/support/tickets')
      this.setData({ tickets: tickets.map((item) => ({ ...item, statusText: statusLabel(item.status) })) })
    } catch (error) { this.setData({ errorMessage: error?.message || '工单加载失败' }) }
    finally { this.setData({ loading: false }) }
  },
  updateTitle(event) { this.setData({ title: event.detail.value }) },
  updateDescription(event) { this.setData({ description: event.detail.value }) },
  async submit(event) {
    if (this.data.submitting) return
    const title = String(event.detail.value.title || '').trim()
    const description = String(event.detail.value.description || '').trim()
    if (!title) return wx.showToast({ title: '请填写问题标题', icon: 'none' })
    if (!description) return wx.showToast({ title: '请填写问题详情', icon: 'none' })
    this.setData({ submitting: true })
    try {
      await request('/customer/support/tickets', { method: 'POST', data: { title, description } })
      wx.showToast({ title: '已提交', icon: 'success' })
      this.setData({ title: '', description: '' })
      await this.loadTickets()
    } catch (error) { showError(error, '提交失败') }
    finally { this.setData({ submitting: false }) }
  }
})
