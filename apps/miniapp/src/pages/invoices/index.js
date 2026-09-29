const { request } = require('../../utils/api')
const sessionStore = require('../../utils/session')
const { statusLabel, yuan, showError } = require('../../utils/presenter')

Page({
  data: { loading: true, submitting: false, errorMessage: '', orders: [], invoices: [], orderIndex: 0 },
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
      const [orders, invoices] = await Promise.all([request('/charging/orders/mine'), request('/customer/finance/invoices')])
      const invoiced = new Set(invoices.map((item) => item.orderId))
      this.setData({
        orders: orders.filter((item) => item.status === 'COMPLETED' && item.paidAmountMinor >= item.payableAmountMinor && item.payableAmountMinor > 0 && !invoiced.has(item.orderId)),
        invoices: invoices.map((item) => ({
          ...item,
          statusText: statusLabel(item.status),
          amountYuan: yuan(item.amountMinor)
        })),
        orderIndex: 0
      })
    } catch (error) { this.setData({ errorMessage: error?.message || '加载失败' }) }
    finally { this.setData({ loading: false }) }
  },
  chooseOrder(event) { this.setData({ orderIndex: Number(event.detail.value) }) },
  async submit(event) {
    if (this.data.submitting) return
    const order = this.data.orders[this.data.orderIndex]
    if (!order) return wx.showToast({ title: '请选择可开票订单', icon: 'none' })
    const values = event.detail.value
    const title = String(values.title || '').trim()
    const taxNumber = String(values.taxNumber || '').trim().toUpperCase()
    const email = String(values.email || '').trim()
    if (!title) return wx.showToast({ title: '请填写发票抬头', icon: 'none' })
    if (taxNumber && !/^[A-Z0-9]{15,20}$/.test(taxNumber)) return wx.showToast({ title: '税号格式不正确', icon: 'none' })
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return wx.showToast({ title: '邮箱格式不正确', icon: 'none' })
    this.setData({ submitting: true })
    try {
      await request('/customer/finance/invoices', {
        method: 'POST',
        data: { orderId: order.orderId, title, taxNumber, email }
      })
      wx.showToast({ title: '申请已提交', icon: 'success' })
      await this.loadData()
    } catch (error) { showError(error, '提交失败') }
    finally { this.setData({ submitting: false }) }
  },
  openInvoice(event) {
    const url = event.currentTarget.dataset.url
    if (url) wx.navigateTo({ url: `/pages/webview/index?url=${encodeURIComponent(url)}` })
  }
})
