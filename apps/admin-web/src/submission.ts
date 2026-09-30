export class SubmissionKeys {
  private entries = new Map<string, { fingerprint: string; key: string }>()

  forRequest(operation: string, tenantId: string, body: unknown): string {
    const fingerprint = JSON.stringify({ tenantId, body })
    const existing = this.entries.get(operation)
    if (existing?.fingerprint === fingerprint) return existing.key
    const key = crypto.randomUUID()
    this.entries.set(operation, { fingerprint, key })
    return key
  }

  completed(operation: string) { this.entries.delete(operation) }
  clear() { this.entries.clear() }
}
