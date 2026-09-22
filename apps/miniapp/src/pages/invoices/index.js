const { request } = require('../../utils/api')

Page({
  data: { orders: [], invoices: [], orderIndex: 0 },
  async onShow() {
    if (!wx.getStorageSync('access_token')) return wx.showToast({ title: '请先登录', icon: 'none' })
    try {
      const [orders, invoices] = await Promise.all([request('/charging/orders/mine'), request('/customer/finance/invoices')])
      const invoiced = new Set(invoices.map(item => item.orderId))
      this.setData({
        orders: orders.filter(item => item.status === 'COMPLETED' && item.paidAmountMinor >= item.payableAmountMinor && item.payableAmountMinor > 0 && !invoiced.has(item.orderId)),
        invoices
      })
    } catch (error) { wx.showToast({ title: error?.message || '加载失败', icon: 'none' }) }
  },
  chooseOrder(event) { this.setData({ orderIndex: Number(event.detail.value) }) },
  async submit(event) {
    const order = this.data.orders[this.data.orderIndex]
    if (!order) return wx.showToast({ title: '请选择可开票订单', icon: 'none' })
    try {
      await request('/customer/finance/invoices', 'POST', { orderId: order.orderId, ...event.detail.value })
      wx.showToast({ title: '申请已提交', icon: 'success' })
      this.onShow()
    } catch (error) { wx.showToast({ title: error?.message || '提交失败', icon: 'none' }) }
  }
})
