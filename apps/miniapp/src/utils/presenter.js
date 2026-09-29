const STATUS_LABELS = Object.freeze({
  CREATED: '待启动', START_PENDING: '启动中', CHARGING: '充电中',
  STOP_PENDING: '停止中', COMPLETED: '已完成', CANCELLED: '已取消', FAILED: '异常',
  PROCESSING: '处理中', SUCCEEDED: '已成功', REFUNDED: '已退款', CLOSED: '已关闭',
  SUBMITTED: '已申请', ISSUED: '已开票', REJECTED: '已驳回',
  OPEN: '待处理', IN_PROGRESS: '处理中', RESOLVED: '已解决', ACTIVE: '正常', FROZEN: '已冻结'
})

function statusLabel(status) {
  return STATUS_LABELS[status] || status || '未知'
}

function yuan(value) {
  const number = Number(value)
  return Number.isFinite(number) ? (number / 100).toFixed(2) : '0.00'
}

function kilowattHours(value) {
  const number = Number(value)
  return Number.isFinite(number) ? (number / 1000).toFixed(2) : '0.00'
}

function message(error, fallback = '操作失败，请稍后重试') {
  return error?.message || fallback
}

function showError(error, fallback) {
  wx.showToast({ title: message(error, fallback), icon: 'none', duration: 2800 })
}

function confirm(content, title = '请确认') {
  return new Promise((resolve) => wx.showModal({
    title,
    content,
    confirmColor: '#087d69',
    success: ({ confirm: accepted }) => resolve(accepted),
    fail: () => resolve(false)
  }))
}

module.exports = { statusLabel, yuan, kilowattHours, message, showError, confirm }
