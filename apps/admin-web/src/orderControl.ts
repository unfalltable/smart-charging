export type OperatorOrder = { id: string; orderNo: string; status: string }

export function prepareOperatorStop(order: OperatorOrder, reason: string,
  confirmAction: (message: string) => boolean): { reason: string } | null {
  if (order.status !== 'CHARGING') return null
  const normalizedReason = reason.trim()
  if (!normalizedReason || normalizedReason.length > 500) throw new Error('请填写 1–500 字的停止原因')
  if (!confirmAction(`确认停止订单 ${order.orderNo} 的充电？\n停止指令提交后仍需等待设备确认，不能视为已经断电。`)) return null
  return { reason: normalizedReason }
}
