const { request } = require('../../utils/api')
const platform = require('../../platform/wechat')
const sessionStore = require('../../utils/session')
const { statusLabel, yuan, kilowattHours, showError, confirm } = require('../../utils/presenter')

Page({
  data: { loading: false, loggedIn: false, errorMessage: '', actionOrderId: '', orders: [] },
  async onShow() {
    const loggedIn = sessionStore.isLoggedIn()
    this.setData({ loggedIn })
    if (!loggedIn) {
      this.setData({ loading: false, errorMessage: '', orders: [] })
      return
    }
    await this.loadOrders()
  },
  async onPullDownRefresh() {
    try { if (sessionStore.isLoggedIn()) await this.loadOrders() }
    finally { wx.stopPullDownRefresh() }
  },
  async loadOrders() {
    this.setData({ loading: true, errorMessage: '' })
    try {
      const orders = await request('/charging/orders/mine')
      this.setData({ orders: orders.map((order) => ({
        ...order,
        canStop: order.status === 'CHARGING',
        canPay: order.status === 'COMPLETED' && order.paidAmountMinor < order.payableAmountMinor,
        statusText: statusLabel(order.status),
        energyKwh: kilowattHours(order.energyWh),
        amountYuan: yuan(order.payableAmountMinor),
        paidYuan: yuan(order.paidAmountMinor)
      })) })
    } catch (error) {
      if (error.code === 'LOGIN_REQUIRED') {
        sessionStore.clearSession()
        this.setData({ loggedIn: false, orders: [] })
      }
      this.setData({ errorMessage: error.message || '订单加载失败' })
    } finally {
      this.setData({ loading: false })
    }
  },
  goToLogin() { sessionStore.goToLogin() },
  async stopOrder(event) {
    const orderId = event.currentTarget.dataset.orderId
    if (this.data.actionOrderId) return
    const accepted = await confirm('停止后将按实际充电量或时长结算，确定停止充电吗？', '停止充电')
    if (!accepted) return
    this.setData({ actionOrderId: orderId })
    try {
      await request(`/charging/orders/${orderId}/stop`, { method: 'POST' })
      wx.showToast({ title: '停止指令已发送', icon: 'success' })
      await this.loadOrders()
    } catch (error) {
      showError(error, '停止失败')
    } finally { this.setData({ actionOrderId: '' }) }
  },
  async payOrder(event) {
    const orderId = event.currentTarget.dataset.orderId
    if (this.data.actionOrderId) return
    this.setData({ actionOrderId: orderId })
    try {
      await platform.subscribeNotifications(getApp().globalData.notificationTemplateIds)
      const intent = await request('/payments', {
        method: 'POST',
        data: { orderId, channel: 'WECHAT' },
        idempotencyKey: `pay-${orderId}-${Date.now()}`
      })
      await platform.pay(intent.clientParameters)
      wx.showToast({ title: '支付完成，正在确认', icon: 'none', duration: 1800 })
      setTimeout(() => this.loadOrders(), 1200)
    } catch (error) {
      if (!String(error?.errMsg || '').includes('cancel')) showError(error, '支付未完成')
    } finally { this.setData({ actionOrderId: '' }) }
  }
})
