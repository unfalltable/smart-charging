export type ReconciliationRow = { merchantOrderNo: string; providerTransactionNo: string; amountMinor: number }

export function parseReconciliationRows(source: string): ReconciliationRow[] {
  const parsed: unknown = JSON.parse(source)
  const rows = Array.isArray(parsed) ? parsed : (parsed as { rows?: unknown } | null)?.rows
  if (!Array.isArray(rows) || rows.length > 10000) throw new Error('账单需为交易行数组，最多 10000 行')
  return rows.map((value: unknown, index: number) => {
    if (!value || typeof value !== 'object') throw new Error(`账单第 ${index + 1} 行格式错误`)
    const row = value as Record<string, unknown>
    if (typeof row.merchantOrderNo !== 'string' || !row.merchantOrderNo.trim() || row.merchantOrderNo.length > 64
      || typeof row.providerTransactionNo !== 'string' || !row.providerTransactionNo.trim() || row.providerTransactionNo.length > 128
      || typeof row.amountMinor !== 'number' || !Number.isSafeInteger(row.amountMinor) || row.amountMinor < 0) {
      throw new Error(`账单第 ${index + 1} 行缺少有效商户单号、支付机构流水号或整数分金额`)
    }
    return { merchantOrderNo: row.merchantOrderNo.trim(), providerTransactionNo: row.providerTransactionNo.trim(), amountMinor: row.amountMinor }
  })
}
