const { request } = require('../../utils/api')
const platform = require('../../platform/wechat')
const sessionStore = require('../../utils/session')
const { statusLabel, yuan, kilowattHours, showError, confirm } = require('../../utils/presenter')

const ACTIVE_STATES = ['CREATED', 'START_PENDING', 'CHARGING', 'STOP_PENDING']

Page({
  data: { loading: false, loggedIn: false, errorMessage: '', actionOrderId: '', orders: [] },
  async onShow() {
    this._visible = true
    this._paymentKeys = this._paymentKeys || new Map()
    this._confirmingPayments = this._confirmingPayments || new Set()
    const loggedIn = sessionStore.isLoggedIn()
    const identity = sessionStore.getSession()
    const identityKey = `${identity.tenantId}/${identity.customerId}`
    if (this._identityKey !== identityKey) {
      this._identityKey = identityKey
      this._paymentKeys.clear(); this._confirmingPayments.clear()
      this.setData({ orders: [], errorMessage: '', actionOrderId: '' })
    }
    this.setData({ loggedIn })
    if (!loggedIn) {
      this._paymentKeys.clear(); this._confirmingPayments.clear()
      this.setData({ loading: false, errorMessage: '', orders: [] })
      return
    }
    await this.loadOrders()
  },
  onHide() { this._visible = false; clearTimeout(this._pollTimer) },
  onUnload() { this.onHide() },
  async onPullDownRefresh() {
    try { if (sessionStore.isLoggedIn()) await this.loadOrders() }
    finally { wx.stopPullDownRefresh() }
  },
  schedulePoll() {
    clearTimeout(this._pollTimer)
    if (!this._visible || !this.data.loggedIn) return
    if (this.data.orders.some(order => ACTIVE_STATES.includes(order.status)) || this._confirmingPayments?.size) {
      this._pollTimer = setTimeout(() => this.loadOrders({ quiet: true }), 5000)
    }
  },
  async loadOrders(options = {}) {
    if (!sessionStore.isLoggedIn()) return
    const identity = sessionStore.getSession()
    const identityKey = `${identity.tenantId}/${identity.customerId}`
    if (this._loadInFlight) {
      if (this._loadingIdentity !== identityKey) return this._loadInFlight.then(() => this.loadOrders(options))
      return this._loadInFlight
    }
    this._loadingIdentity = identityKey
    if (!options.quiet) this.setData({ loading: true })
    this.setData({ errorMessage: '' })
    this._loadInFlight = (async () => {
      try {
        const [orders, payments] = await Promise.all([
          request('/charging/orders/mine'),
          this._confirmingPayments?.size ? request('/customer/finance/payments') : Promise.resolve([])
        ])
        const current = sessionStore.getSession()
        if (current.customerId !== identity.customerId || current.tenantId !== identity.tenantId) return
        this.setData({ orders: orders.map((order) => {
          const latestPayment = payments.find(payment => payment.orderId === order.orderId)
          if (latestPayment && ['FAILED', 'CLOSED'].includes(latestPayment.status)) {
            this._confirmingPayments?.delete(order.orderId); this._paymentKeys?.delete(order.orderId)
          }
          const refunded = Number(order.refundedAmountMinor || 0)
          const outstanding = Math.max(0, order.payableAmountMinor - order.paidAmountMinor - refunded)
          const paid = outstanding === 0
          if (paid) { this._confirmingPayments?.delete(order.orderId); this._paymentKeys?.delete(order.orderId) }
          return {
            ...order,
            canStop: order.status === 'CHARGING',
            canPay: order.status === 'COMPLETED' && !paid && !this._confirmingPayments?.has(order.orderId),
            confirmingPayment: this._confirmingPayments?.has(order.orderId) || false,
            statusText: statusLabel(order.status),
            energyKwh: kilowattHours(order.energyWh),
            amountYuan: yuan(order.payableAmountMinor),
            paidYuan: yuan(order.paidAmountMinor),
            refundedYuan: yuan(refunded), outstandingYuan: yuan(outstanding)
          }
        }) })
      } catch (error) {
        const current = sessionStore.getSession()
        if (current.customerId && current.customerId !== identity.customerId) return
        if (error.code === 'LOGIN_REQUIRED') this.setData({ loggedIn: false, orders: [] })
        this.setData({ errorMessage: error.message || '订单加载失败' })
      } finally {
        this.setData({ loading: false })
        this._loadInFlight = null
        this.schedulePoll()
      }
    })()
    return this._loadInFlight
  },
  goToLogin() { sessionStore.goToLogin() },
  async stopOrder(event) {
    const orderId = event.currentTarget.dataset.orderId
    if (this.data.actionOrderId) return
    this.setData({ actionOrderId: orderId })
    try {
      if (!(await confirm('停止后将按实际充电量或时长结算，确定停止充电吗？', '停止充电'))) return
      await request('/charging/orders/' + orderId + '/stop', { method: 'POST' })
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
    this._paymentKeys = this._paymentKeys || new Map()
    this._confirmingPayments = this._confirmingPayments || new Set()
    if (!this._paymentKeys.has(orderId)) this._paymentKeys.set(orderId, 'pay-' + orderId + '-' + Date.now() + '-' + Math.random().toString(36).slice(2))
    try {
      await platform.subscribeNotifications(getApp().globalData.notificationTemplateIds)
      const intent = await request('/payments', {
        method: 'POST',
        data: { orderId, channel: 'WECHAT' },
        idempotencyKey: this._paymentKeys.get(orderId)
      })
      if (['FAILED', 'CLOSED'].includes(intent.status)) {
        this._paymentKeys.delete(orderId)
        this._confirmingPayments.delete(orderId)
        wx.showToast({ title: intent.status === 'FAILED' ? '原支付失败，请重新发起' : '原支付已关闭，请重新发起', icon: 'none', duration: 2200 })
        return
      }
      if (intent.status !== 'SUCCEEDED') await platform.pay(intent.clientParameters)
      this._confirmingPayments.add(orderId)
      wx.showToast({ title: '正在确认支付结果', icon: 'none', duration: 1800 })
    } catch (error) {
      if (!String(error?.errMsg || '').includes('cancel')) showError(error, '支付未完成')
    } finally {
      await this.loadOrders()
      this.setData({ actionOrderId: '' })
    }
  }
})
