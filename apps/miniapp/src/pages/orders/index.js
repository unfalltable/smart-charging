const { request } = require('../../utils/api')
const platform = require('../../platform/wechat')

Page({
  data: { loading: false, orders: [] },
  async onShow() {
    if (!wx.getStorageSync('access_token')) return
    this.setData({ loading: true })
    try {
      const orders = await request('/charging/orders/mine')
      this.setData({ orders: orders.map((order) => ({
        ...order,
        canStop: order.status === 'CHARGING',
        canPay: order.status === 'COMPLETED' && order.paidAmountMinor < order.payableAmountMinor,
        energyKwh: (order.energyWh / 1000).toFixed(2),
        amountYuan: (order.payableAmountMinor / 100).toFixed(2),
        paidYuan: (order.paidAmountMinor / 100).toFixed(2)
      })) })
    } catch {
      wx.showToast({ title: '订单加载失败', icon: 'none' })
    } finally {
      this.setData({ loading: false })
    }
  },
  async stopOrder(event) {
    const orderId = event.currentTarget.dataset.orderId
    try {
      await request(`/charging/orders/${orderId}/stop`, 'POST')
      wx.showToast({ title: '停止指令已发送', icon: 'success' })
      this.onShow()
    } catch (error) {
      wx.showToast({ title: error?.message || '停止失败', icon: 'none' })
    }
  },
  async payOrder(event) {
    const orderId = event.currentTarget.dataset.orderId
    try {
      await platform.subscribeNotifications(getApp().globalData.notificationTemplateIds)
      const intent = await request('/payments', 'POST', { orderId, channel: 'WECHAT' },
        `pay-${orderId}-${Date.now()}`)
      await platform.pay(intent.clientParameters)
      wx.showToast({ title: '支付成功', icon: 'success' })
      this.onShow()
    } catch (error) {
      wx.showToast({ title: error?.message || '支付未完成', icon: 'none' })
    }
  }
})
