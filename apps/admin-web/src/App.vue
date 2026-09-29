<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { getJson, loadSessionContext, patchJson, postJson, selectTenant,
  type DashboardSummary, type SessionContext } from './api/client'
import { changePassword, initializeAuth, login, logout } from './auth'

type Page = 'platform' | 'dashboard' | 'organization' | 'assets' | 'orders' | 'tariffs' | 'finance' | 'operations' | 'legal' | 'access' | 'audit' | 'security'
type Row = Record<string, unknown>
type CredentialResult = { temporaryPassword: string; user: Row }
type OrganizationNode = {
  id: string; parentId: string | null; code: string; name: string; organizationType: string
  hierarchyLevel: number; contactName: string | null; contactMobile: string | null; status: string
  version: number; stationCount: number; deviceCount: number; connectorCount: number; customerCount: number
}
type OrganizationTree = {
  root: OrganizationNode
  children: OrganizationNode[]
  totals: { stationCount: number; deviceCount: number; connectorCount: number; customerCount: number }
}
type PlatformOverview = {
  activeTenants: number; totalTenants: number; stations: number; devices: number; onlineDevices: number
  successfulPaymentsToday: number; todayNetRevenueMinor: number
  confirmedPlatformServiceFeeMinor: number; pendingPlatformServiceFeeMinor: number
}

const page = ref<Page>('dashboard')
const loading = ref(false)
const loadError = ref('')
const notice = ref('')
const authenticated = ref(false)
const mustChangePassword = ref(false)
const sessionContext = ref<SessionContext | null>(null)
const currentTenantId = ref('')
const summary = ref<DashboardSummary>({ onlineDevices: 0, totalDevices: 0, availableConnectors: 0, activeOrders: 0, todayRevenueMinor: 0 })
const stations = ref<Row[]>([]), devices = ref<Row[]>([]), connectors = ref<Row[]>([])
const orders = ref<Row[]>([]), tariffs = ref<Row[]>([]), payments = ref<Row[]>([]), refunds = ref<Row[]>([])
const settlements = ref<Row[]>([]), alarms = ref<Row[]>([]), workOrders = ref<Row[]>([]), auditRows = ref<Row[]>([])
const merchantChannels = ref<Row[]>([]), memberships = ref<Row[]>([]), settlementRules = ref<Row[]>([])
const invoices = ref<Row[]>([]), agreements = ref<Row[]>([])
const profitSharingReceivers = ref<Row[]>([]), profitSharingPolicies = ref<Row[]>([]), profitSharingOrders = ref<Row[]>([])
const profitSharingReturns = ref<Row[]>([])
const organizationTree = ref<OrganizationTree | null>(null)
const platformTenants = ref<Row[]>([]), loginEvents = ref<Row[]>([])
const temporaryCredential = ref<{ username: string; password: string } | null>(null)
const roleDrafts = reactive<Record<string, string>>({})
const loginForm = reactive({ username: '', password: '' })
const passwordForm = reactive({ currentPassword: '', newPassword: '', confirmation: '' })
const showLoginPassword = ref(false)
const showCurrentPassword = ref(false)
const showNewPassword = ref(false)
const showConfirmationPassword = ref(false)

const stationForm = reactive({ organizationId: '', code: '', name: '', address: '', timezone: 'Asia/Shanghai', status: 'DRAFT' })
const deviceForm = reactive({ stationId: '', deviceCode: '', protocolCode: '', productModel: '', connectorCount: null as number | null, ratedPowerW: null as number | null })
const tariffForm = reactive({ name: '', billingMode: 'DURATION', durationUnitPriceMinor: null as number | null, energyUnitPriceMinor: null as number | null, minimumAmountMinor: null as number | null })
const workOrderForm = reactive({ title: '', description: '', priority: 'NORMAL', assigneeSubject: '' })
const refundForm = reactive({ paymentId: '', amountMinor: 0, reason: '' })
const settlementForm = reactive({ ruleId: '', periodStart: '', periodEnd: '' })
const merchantForm = reactive({ organizationId: '', channel: 'WECHAT', merchantId: '', applicationId: '', secretReference: '', notifyUrl: '', refundNotifyUrl: '', profitSharingRequired: true, status: 'ACTIVE' })
const membershipForm = reactive({ tenantId: '', username: '', email: '', displayName: '', roleCode: 'OPERATOR' })
const tenantForm = reactive({ code: '', displayName: '' })
const settlementRuleForm = reactive({ organizationId: '', name: '', beneficiaryCode: '', shareBasisPoints: 10000, platformServiceFeeBasisPoints: 0, fixedServiceFeeMinor: 0, effectiveFrom: '', effectiveUntil: null as string | null })
const agreementForm = reactive({ documentCode: 'SERVICE_TERMS', version: '', title: '', contentUrl: '', contentHash: '', effectiveAt: '' })
const organizationForm = reactive({ parentId: '', code: '', name: '', organizationType: 'SECOND_TIER_PARTNER', contactName: '', contactMobile: '' })
const rootOrganizationForm = reactive({ name: '', organizationType: 'REGIONAL_OPERATOR', contactName: '', contactMobile: '', status: 'ACTIVE', version: 0 })
const profitSharingReceiverForm = reactive({ ownerType: 'ORGANIZATION', organizationId: '', merchantChannelId: '', receiverAccount: '', receiverName: '', relationType: 'PARTNER', customRelation: null as string | null })
const profitSharingPolicyForm = reactive({ sourceOrganizationId: '', receiverId: '', basisPoints: 0, effectiveFrom: '', effectiveUntil: null as string | null })

const onlineRate = computed(() => summary.value.totalDevices === 0 ? 0 : Math.round(summary.value.onlineDevices * 100 / summary.value.totalDevices))
const revenue = computed(() => money(summary.value.todayRevenueMinor))
const currentTenant = computed(() => sessionContext.value?.tenants.find((tenant) => tenant.id === currentTenantId.value))
const organizations = computed(() => organizationTree.value ? [organizationTree.value.root, ...organizationTree.value.children] : [])
const organizationParents = computed(() => organizations.value.filter(row => row.status === 'ACTIVE' && row.hierarchyLevel < 3))
const selectedOrganizationParent = computed(() => organizations.value.find(row => row.id === organizationForm.parentId))
const passwordRules = computed(() => ({
  length: passwordForm.newPassword.length >= 12 && passwordForm.newPassword.length <= 128,
  upper: /[A-Z]/.test(passwordForm.newPassword),
  lower: /[a-z]/.test(passwordForm.newPassword),
  digit: /[0-9]/.test(passwordForm.newPassword),
  symbol: /[^A-Za-z0-9]/.test(passwordForm.newPassword)
}))
const passwordReady = computed(() => Boolean(passwordForm.currentPassword)
  && Object.values(passwordRules.value).every(Boolean)
  && passwordForm.newPassword === passwordForm.confirmation)
const platformOverview = computed(() => platformTenants.value.reduce<PlatformOverview>((total, row) => ({
  activeTenants: total.activeTenants + (row.status === 'ACTIVE' ? 1 : 0),
  totalTenants: total.totalTenants + 1,
  stations: total.stations + Number(row.stations ?? 0),
  devices: total.devices + Number(row.devices ?? 0),
  onlineDevices: total.onlineDevices + Number(row.onlineDevices ?? 0),
  successfulPaymentsToday: total.successfulPaymentsToday + Number(row.successfulPaymentsToday ?? 0),
  todayNetRevenueMinor: total.todayNetRevenueMinor + Number(row.todayNetRevenueMinor ?? 0),
  confirmedPlatformServiceFeeMinor: total.confirmedPlatformServiceFeeMinor + Number(row.confirmedPlatformServiceFeeMinor ?? 0),
  pendingPlatformServiceFeeMinor: total.pendingPlatformServiceFeeMinor + Number(row.pendingPlatformServiceFeeMinor ?? 0)
}), { activeTenants: 0, totalTenants: 0, stations: 0, devices: 0, onlineDevices: 0,
  successfulPaymentsToday: 0, todayNetRevenueMinor: 0, confirmedPlatformServiceFeeMinor: 0,
  pendingPlatformServiceFeeMinor: 0 }))
const titles: Record<Page, [string, string]> = {
  platform: ['PLATFORM', '平台与租户'], dashboard: ['OPERATIONS', '运营总览'], organization: ['CHANNEL', '渠道组织'], assets: ['ASSETS', '场站设备'], orders: ['ORDERS', '充电订单'],
  tariffs: ['PRICING', '计费策略'], finance: ['FINANCE', '支付与结算'], operations: ['SERVICE', '告警工单'],
  legal: ['LEGAL', '协议与合规'], access: ['ACCESS', '账号与权限'], audit: ['AUDIT', '审计日志'], security: ['SECURITY', '账号安全']
}
const visiblePages = computed(() => (Object.keys(titles) as Page[]).filter(item => {
  if (['platform', 'access'].includes(item)) return Boolean(sessionContext.value?.platformAdministrator)
  if (item === 'security') return true
  if (!currentTenant.value) return false
  if (sessionContext.value?.platformAdministrator) return true
  const roles = currentTenant.value.roles
  if (item === 'organization') return roles.some(role => ['TENANT_ADMIN', 'OPERATOR', 'FINANCE'].includes(role))
  if (['dashboard', 'assets', 'orders', 'tariffs'].includes(item)) return roles.some(role => ['TENANT_ADMIN', 'OPERATOR'].includes(role))
  if (item === 'finance') return roles.some(role => ['TENANT_ADMIN', 'FINANCE'].includes(role))
  if (item === 'operations') return roles.some(role => ['TENANT_ADMIN', 'OPERATOR', 'SUPPORT'].includes(role))
  if (item === 'legal') return roles.includes('TENANT_ADMIN')
  if (item === 'audit') return roles.some(role => ['TENANT_ADMIN', 'AUDITOR'].includes(role))
  return false
}))

function money(value: unknown) { return `¥ ${(Number(value ?? 0) / 100).toFixed(2)}` }
function date(value: unknown) { return value ? new Date(String(value)).toLocaleString('zh-CN') : '—' }
function short(value: unknown) { const text = String(value ?? '—'); return text.length > 24 ? `${text.slice(0, 21)}…` : text }
function organizationType(value: unknown) { return ({ REGIONAL_OPERATOR: '区域运营商', FIRST_TIER_CONTRACTOR: '一级分包商', DIRECT_BRANCH: '直营网点', SECOND_TIER_PARTNER: '二级合作商', THIRD_TIER_FRANCHISE: '三级加盟商', SITE_PARTNER: '场地方' } as Record<string, string>)[String(value)] ?? String(value) }

function alignOrganizationType() {
  organizationForm.organizationType = selectedOrganizationParent.value?.hierarchyLevel === 2 ? 'THIRD_TIER_FRANCHISE' : 'SECOND_TIER_PARTNER'
}

function applyOrganizationTree(tree: OrganizationTree) {
  organizationTree.value = tree
  organizationForm.parentId = tree.root.id
  alignOrganizationType()
  if (!stationForm.organizationId || !organizations.value.some(row => row.id === stationForm.organizationId && row.status === 'ACTIVE')) stationForm.organizationId = tree.root.id
  if (!settlementRuleForm.organizationId || !organizations.value.some(row => row.id === settlementRuleForm.organizationId && row.status === 'ACTIVE')) settlementRuleForm.organizationId = tree.root.id
  if (!profitSharingReceiverForm.organizationId || !organizations.value.some(row => row.id === profitSharingReceiverForm.organizationId && row.status === 'ACTIVE')) profitSharingReceiverForm.organizationId = tree.root.id
  if (!merchantForm.organizationId || !organizations.value.some(row => row.id === merchantForm.organizationId && row.status === 'ACTIVE')) merchantForm.organizationId = tree.root.id
  if (!profitSharingReceiverForm.merchantChannelId || !merchantChannels.value.some(row => row.id === profitSharingReceiverForm.merchantChannelId && row.status === 'ACTIVE')) profitSharingReceiverForm.merchantChannelId = String(merchantChannels.value.find(row => row.status === 'ACTIVE')?.id || '')
  if (!profitSharingPolicyForm.sourceOrganizationId || !organizations.value.some(row => row.id === profitSharingPolicyForm.sourceOrganizationId && row.status === 'ACTIVE')) profitSharingPolicyForm.sourceOrganizationId = tree.root.id
  Object.assign(rootOrganizationForm, { name: tree.root.name, organizationType: tree.root.organizationType, contactName: tree.root.contactName ?? '', contactMobile: tree.root.contactMobile ?? '', status: tree.root.status, version: tree.root.version })
}

async function run(action: () => Promise<void>, success = '操作成功') {
  loadError.value = ''; notice.value = ''; loading.value = true
  try { await action(); notice.value = success }
  catch (error) { loadError.value = error instanceof Error ? error.message : '操作失败' }
  finally { loading.value = false }
}

async function fetchPage(target: Page) {
  if (target === 'platform') platformTenants.value = await getJson<Row[]>('/platform/tenants')
  if (target === 'dashboard') summary.value = await getJson<DashboardSummary>('/operations/dashboard')
  if (target === 'organization') applyOrganizationTree(await getJson<OrganizationTree>('/admin/organizations/tree'))
  if (target === 'assets') {
    const [tree, stationRows, deviceRows, connectorRows] = await Promise.all([getJson<OrganizationTree>('/admin/organizations/tree'),
      getJson<Row[]>('/admin/assets/stations'), getJson<Row[]>('/admin/assets/devices'), getJson<Row[]>('/admin/assets/connectors')])
    applyOrganizationTree(tree); stations.value = stationRows; devices.value = deviceRows; connectors.value = connectorRows
  }
  if (target === 'orders') orders.value = await getJson<Row[]>('/admin/operations/orders')
  if (target === 'tariffs') tariffs.value = await getJson<Row[]>('/admin/tariffs')
  if (target === 'finance') {
    const [tree, paymentRows, refundRows, settlementRows, channelRows, ruleRows, invoiceRows, receiverRows, policyRows, sharingRows, sharingReturnRows] = await Promise.all([
      getJson<OrganizationTree>('/admin/organizations/tree'), getJson<Row[]>('/admin/finance/payments'), getJson<Row[]>('/admin/finance/refunds'),
      getJson<Row[]>('/admin/finance/settlements'), getJson<Row[]>('/admin/finance/merchant-channels'),
      getJson<Row[]>('/admin/finance/settlement-rules'), getJson<Row[]>('/admin/finance/invoices'),
      getJson<Row[]>('/admin/finance/profit-sharing/receivers'), getJson<Row[]>('/admin/finance/profit-sharing/policies'),
      getJson<Row[]>('/admin/finance/profit-sharing/orders'), getJson<Row[]>('/admin/finance/profit-sharing/returns')])
    applyOrganizationTree(tree); payments.value = paymentRows; refunds.value = refundRows; settlements.value = settlementRows
    merchantChannels.value = channelRows
    if (!merchantChannels.value.some(row => row.id === profitSharingReceiverForm.merchantChannelId && row.status === 'ACTIVE')) {
      profitSharingReceiverForm.merchantChannelId = String(merchantChannels.value.find(row => row.status === 'ACTIVE')?.id || '')
    }
    settlementRules.value = ruleRows; invoices.value = invoiceRows
    profitSharingReceivers.value = receiverRows; profitSharingPolicies.value = policyRows; profitSharingOrders.value = sharingRows
    profitSharingReturns.value = sharingReturnRows
  }
  if (target === 'operations') [alarms.value, workOrders.value] = await Promise.all([
    getJson<Row[]>('/admin/operations/alarms'), getJson<Row[]>('/admin/operations/work-orders')])
  if (target === 'legal') agreements.value = await getJson<Row[]>('/admin/legal/agreements')
  if (target === 'access') {
    [memberships.value, loginEvents.value, platformTenants.value] = await Promise.all([
      getJson<Row[]>('/platform/users'), getJson<Row[]>('/platform/users/login-events'),
      getJson<Row[]>('/platform/tenants')])
    for (const row of memberships.value) roleDrafts[String(row.id)] = String(row.roleCode ?? 'OPERATOR')
    if (!membershipForm.tenantId || !platformTenants.value.some(row => row.id === membershipForm.tenantId && row.status === 'ACTIVE')) {
      membershipForm.tenantId = String(platformTenants.value.find(row => row.status === 'ACTIVE')?.id ?? '')
    }
  }
  if (target === 'audit') auditRows.value = await getJson<Row[]>('/admin/operations/audit')
}

async function load(target: Page = page.value) {
  page.value = target
  await run(() => fetchPage(target), '')
}
async function refresh(target: Page, action: () => Promise<unknown>, message: string) {
  await run(async () => { await action(); await fetchPage(target) }, message)
}

async function createStation() { await refresh('assets', () => postJson('/admin/assets/stations', stationForm), '场站已创建'); Object.assign(stationForm, { organizationId: organizationTree.value?.root.id ?? '', code: '', name: '', address: '', timezone: 'Asia/Shanghai', status: 'DRAFT' }) }
async function createDevice() { await refresh('assets', () => postJson('/admin/assets/devices', { ...deviceForm, firmwareVersion: null }), '设备及端口已创建'); Object.assign(deviceForm, { stationId: '', deviceCode: '', protocolCode: '', productModel: '', connectorCount: null, ratedPowerW: null }) }
async function rotateCredential(row: Row) { if (!confirm(`轮换设备 ${row.deviceCode} 的接入密钥？旧密钥将在网关缓存过期后失效。`)) return; await run(async () => { const issued = await postJson<Row>(`/admin/assets/devices/${row.id}/credential/rotate`, {}); await navigator.clipboard.writeText(String(issued.secret)); notice.value = `新密钥已复制到剪贴板，请立即安全写入设备（指纹 ${issued.fingerprint}）` }, '') }
async function createTariff() { await refresh('tariffs', () => postJson('/admin/tariffs', { ...tariffForm, effectiveFrom: new Date().toISOString(), effectiveUntil: null }), '费率草稿已创建'); tariffForm.name = '' }
async function activateTariff(row: Row) { await refresh('tariffs', () => postJson(`/admin/tariffs/${row.id}/activate`, { version: row.version }), '费率已生效') }
async function cancelOrder(row: Row) { if (confirm(`确认取消订单 ${row.orderNo}？`)) await refresh('orders', () => postJson(`/admin/operations/orders/${row.id}/cancel`, { reason: '运营后台取消' }), '订单已取消') }
async function acknowledgeAlarm(row: Row) { await refresh('operations', () => postJson(`/admin/operations/alarms/${row.id}/acknowledge`, {}), '告警已确认') }
async function resolveAlarm(row: Row) { await refresh('operations', () => postJson(`/admin/operations/alarms/${row.id}/resolve`, {}), '告警已解决') }
async function createWorkOrder() { await refresh('operations', () => postJson('/admin/operations/work-orders', { ...workOrderForm, assigneeSubject: workOrderForm.assigneeSubject || null, alarmId: null, deviceId: null, connectorId: null, dueAt: null }), '工单已创建'); workOrderForm.title = ''; workOrderForm.description = '' }
async function transitionWorkOrder(row: Row, status: string) { await refresh('operations', () => postJson(`/admin/operations/work-orders/${row.id}/transition`, { status, assigneeSubject: row.assigneeSubject || null, version: row.version }), '工单状态已更新') }
async function createRefund() { await refresh('finance', () => postJson('/admin/finance/refunds', refundForm), '退款已提交'); Object.assign(refundForm, { paymentId: '', amountMinor: 0, reason: '' }) }
async function generateSettlement() { await refresh('finance', () => postJson('/admin/finance/settlements', settlementForm), '结算单已生成') }
async function createSettlementRule() { await refresh('finance', () => postJson('/admin/finance/settlement-rules', settlementRuleForm), '结算规则已创建'); settlementRuleForm.name = ''; settlementRuleForm.beneficiaryCode = '' }
async function createOrganization() { await refresh('organization', () => postJson('/admin/organizations', organizationForm), '合作组织已创建'); Object.assign(organizationForm, { parentId: organizationTree.value?.root.id ?? '', code: '', name: '', organizationType: 'SECOND_TIER_PARTNER', contactName: '', contactMobile: '' }) }
async function saveRootOrganization() { const root = organizationTree.value?.root; if (root) await refresh('organization', () => patchJson(`/admin/organizations/${root.id}`, rootOrganizationForm), '一级组织已更新') }
async function changeOrganizationStatus(row: OrganizationNode, status: string) { await refresh('organization', () => patchJson(`/admin/organizations/${row.id}`, { name: row.name, organizationType: row.organizationType, contactName: row.contactName ?? '', contactMobile: row.contactMobile ?? '', status, version: row.version }), '组织状态已更新') }
async function transitionSettlement(row: Row, status: string) { await refresh('finance', () => postJson(`/admin/finance/settlements/${row.id}/transition`, { status }), '结算状态已更新') }
async function createMerchantChannel() { await refresh('finance', () => postJson('/admin/finance/merchant-channels', merchantForm), '商户通道已创建') }
async function registerProfitSharingReceiver() {
  await refresh('finance', () => postJson('/admin/finance/profit-sharing/receivers', {
    ...profitSharingReceiverForm,
    organizationId: profitSharingReceiverForm.ownerType === 'PLATFORM' ? null : profitSharingReceiverForm.organizationId,
    customRelation: profitSharingReceiverForm.relationType === 'CUSTOM' ? profitSharingReceiverForm.customRelation : null
  }), '收款账户已通过支付机构登记')
  Object.assign(profitSharingReceiverForm, { ownerType: 'ORGANIZATION', receiverAccount: '', receiverName: '', relationType: 'PARTNER', customRelation: null })
}
async function createProfitSharingPolicy() { await refresh('finance', () => postJson('/admin/finance/profit-sharing/policies', profitSharingPolicyForm), '逐笔分账规则已生效') }
async function disableProfitSharingReceiver(row: Row) { await refresh('finance', () => patchJson(`/admin/finance/profit-sharing/receivers/${row.id}/disable`, {}), '分账接收方已停用') }
async function disableProfitSharingPolicy(row: Row) { await refresh('finance', () => patchJson(`/admin/finance/profit-sharing/policies/${row.id}/disable`, {}), '逐笔分账规则已停用') }
async function issueInvoice(row: Row) { const invoiceUrl = prompt('请输入电子发票 HTTPS 地址'); if (invoiceUrl) await refresh('finance', () => postJson(`/admin/finance/invoices/${row.id}/issue`, { invoiceUrl }), '发票已开具') }
async function rejectInvoice(row: Row) { const reason = prompt('请输入驳回原因'); if (reason) await refresh('finance', () => postJson(`/admin/finance/invoices/${row.id}/reject`, { reason }), '发票申请已驳回') }
async function redIssueInvoice(row: Row) { const creditNoteUrl = prompt('请输入红字发票 HTTPS 地址'); const reason = creditNoteUrl ? prompt('请输入红冲原因') : null; if (creditNoteUrl && reason) await refresh('finance', () => postJson(`/admin/finance/invoices/${row.id}/red-issue`, { creditNoteUrl, reason }), '红字发票已开具') }
async function createAgreement() { await refresh('legal', () => postJson('/admin/legal/agreements', { ...agreementForm, effectiveAt: agreementForm.effectiveAt ? new Date(agreementForm.effectiveAt).toISOString() : new Date().toISOString() }), '协议版本已发布'); agreementForm.version = ''; agreementForm.title = ''; agreementForm.contentUrl = ''; agreementForm.contentHash = '' }
async function createPlatformUser() {
  await run(async () => {
    const result = await postJson<CredentialResult>('/platform/users', membershipForm)
    temporaryCredential.value = { username: membershipForm.username, password: result.temporaryPassword }
    Object.assign(membershipForm, { username: '', email: '', displayName: '', roleCode: 'OPERATOR' })
    await fetchPage('access')
  }, '账号已创建，请安全交付一次性临时密码')
}
async function changeMembership(row: Row, status: string) {
  const action = status === 'ACTIVE' ? '启用' : '停用'
  if (!confirm(`确定${action}账号 ${row.username}？该账号的现有登录会话将立即失效。`)) return
  await refresh('access', () => patchJson(`/platform/users/${row.id}/status`, { status }), `账号已${action}`)
}
async function changeRole(row: Row) {
  await refresh('access', () => patchJson(`/platform/users/${row.id}/role`, {
    tenantId: row.tenantId, roleCode: roleDrafts[String(row.id)]
  }), '岗位角色已更新，原登录会话已撤销')
}
async function resetPlatformUserPassword(row: Row) {
  if (!confirm(`确定重置账号 ${row.username} 的密码？现有登录会话将立即失效。`)) return
  await run(async () => {
    const result = await postJson<CredentialResult>(`/platform/users/${row.id}/reset-password`, {})
    temporaryCredential.value = { username: String(row.username), password: result.temporaryPassword }
    await fetchPage('access')
  }, '一次性临时密码已生成')
}
async function copyTemporaryCredential() {
  if (!temporaryCredential.value) return
  await navigator.clipboard.writeText(`用户名：${temporaryCredential.value.username}\n临时密码：${temporaryCredential.value.password}`)
  notice.value = '临时凭据已复制；关闭提示后系统不会再次显示该密码'
}
async function createTenant() {
  await run(async () => {
    await postJson('/platform/tenants', tenantForm)
    Object.assign(tenantForm, { code: '', displayName: '' })
    sessionContext.value = await loadSessionContext()
    currentTenantId.value = sessionStorage.getItem('tenant_id') ?? ''
    await fetchPage('platform')
  }, '租户已创建，可在“账号与权限”中分配管理员')
}
async function changeTenantStatus(row: Row, status: string) { await refresh('platform', () => patchJson(`/platform/tenants/${row.id}/status`, { status }), '租户状态已更新') }
async function enterConsole() {
  sessionContext.value = await loadSessionContext()
  currentTenantId.value = sessionStorage.getItem('tenant_id') ?? ''
  if (!currentTenantId.value && sessionContext.value.platformAdministrator) page.value = 'platform'
  else if (!visiblePages.value.includes(page.value)) page.value = visiblePages.value[0] ?? 'platform'
  await load()
}
async function beginLogin() {
  loadError.value = ''; loading.value = true
  try {
    const state = await login(loginForm.username.trim(), loginForm.password)
    authenticated.value = state.authenticated
    mustChangePassword.value = state.mustChangePassword
    loginForm.password = ''
    if (state.authenticated && !state.mustChangePassword) await enterConsole()
  } catch (error) { loadError.value = error instanceof Error ? error.message : '登录失败' }
  finally { loading.value = false }
}
async function submitPasswordChange() {
  loadError.value = ''
  notice.value = ''
  if (passwordForm.newPassword !== passwordForm.confirmation) {
    loadError.value = '两次输入的新密码不一致'
    return
  }
  loading.value = true
  try {
    const firstSignIn = mustChangePassword.value
    const state = await changePassword(passwordForm.currentPassword, passwordForm.newPassword)
    mustChangePassword.value = state.mustChangePassword
    Object.assign(passwordForm, { currentPassword: '', newPassword: '', confirmation: '' })
    showCurrentPassword.value = false; showNewPassword.value = false; showConfirmationPassword.value = false
    await enterConsole()
    if (!firstSignIn) notice.value = '密码已更新，其他设备上的管理会话已全部撤销'
  } catch (error) { loadError.value = error instanceof Error ? error.message : '密码修改失败' }
  finally { loading.value = false }
}
async function signOut() {
  await logout()
  authenticated.value = false
  mustChangePassword.value = false
  sessionContext.value = null
}
function handleAuthenticationExpired() {
  authenticated.value = false
  mustChangePassword.value = false
  sessionContext.value = null
  currentTenantId.value = ''
  loadError.value = '登录状态已失效，请重新登录'
}
async function switchTenant() {
  if (!sessionContext.value) return
  selectTenant(currentTenantId.value, sessionContext.value)
  if (!visiblePages.value.includes(page.value)) page.value = visiblePages.value.find(item => item !== 'platform') ?? 'platform'
  await load(page.value)
}

onMounted(async () => {
  window.addEventListener('admin-auth-expired', handleAuthenticationExpired)
  try {
    const state = await initializeAuth()
    authenticated.value = state.authenticated
    mustChangePassword.value = state.mustChangePassword
    if (state.authenticated && !state.mustChangePassword) await enterConsole()
  }
  catch (error) { loadError.value = error instanceof Error ? error.message : '登录失败' }
})
onBeforeUnmount(() => window.removeEventListener('admin-auth-expired', handleAuthenticationExpired))
</script>

<template>
  <div v-if="!authenticated || mustChangePassword" class="login-screen">
    <form v-if="mustChangePassword" class="login-card" @submit.prevent="submitPasswordChange">
      <span class="brand-mark" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M13.3 2 5.7 13h5.1L9.9 22l8.4-12h-5.2z" /></svg></span>
      <p>FIRST SIGN-IN</p><h1>设置新密码</h1>
      <span>初始密码只能使用一次。新密码需包含大小写字母、数字和特殊字符，长度至少 12 位。</span>
      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
      <label>当前临时密码<span class="password-field"><input v-model="passwordForm.currentPassword" :type="showCurrentPassword ? 'text' : 'password'" autocomplete="current-password" minlength="8" maxlength="128" required><button type="button" :aria-pressed="showCurrentPassword" @click="showCurrentPassword = !showCurrentPassword">{{ showCurrentPassword ? '隐藏' : '显示' }}</button></span></label>
      <label>新密码<span class="password-field"><input v-model="passwordForm.newPassword" :type="showNewPassword ? 'text' : 'password'" autocomplete="new-password" minlength="12" maxlength="128" required><button type="button" :aria-pressed="showNewPassword" @click="showNewPassword = !showNewPassword">{{ showNewPassword ? '隐藏' : '显示' }}</button></span></label>
      <label>确认新密码<span class="password-field"><input v-model="passwordForm.confirmation" :type="showConfirmationPassword ? 'text' : 'password'" autocomplete="new-password" minlength="12" maxlength="128" required><button type="button" :aria-pressed="showConfirmationPassword" @click="showConfirmationPassword = !showConfirmationPassword">{{ showConfirmationPassword ? '隐藏' : '显示' }}</button></span></label>
      <ul class="password-rules" aria-live="polite"><li :class="{ passed: passwordRules.length }">12–128 位</li><li :class="{ passed: passwordRules.upper && passwordRules.lower }">包含大小写字母</li><li :class="{ passed: passwordRules.digit }">包含数字</li><li :class="{ passed: passwordRules.symbol }">包含特殊字符</li><li :class="{ passed: passwordForm.confirmation.length > 0 && passwordForm.newPassword === passwordForm.confirmation }">两次输入一致</li></ul>
      <button :disabled="loading || !passwordReady">{{ loading ? '正在保存…' : '保存密码并进入平台' }}</button>
      <button type="button" class="login-secondary" @click="signOut">返回登录</button>
    </form>
    <form v-else class="login-card" @submit.prevent="beginLogin">
      <span class="brand-mark" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M13.3 2 5.7 13h5.1L9.9 22l8.4-12h-5.2z" /></svg></span>
      <p>SMART CHARGING</p><h1>充电运营平台</h1>
      <span>使用平台分配的管理账号登录，权限按租户与岗位校验。</span>
      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
      <label>用户名<input v-model.trim="loginForm.username" autocomplete="username" autocapitalize="none" spellcheck="false" minlength="3" maxlength="64" required></label>
      <label>密码<span class="password-field"><input v-model="loginForm.password" :type="showLoginPassword ? 'text' : 'password'" autocomplete="current-password" minlength="8" maxlength="128" required><button type="button" :aria-pressed="showLoginPassword" @click="showLoginPassword = !showLoginPassword">{{ showLoginPassword ? '隐藏' : '显示' }}</button></span></label>
      <button :disabled="loading">{{ loading ? '正在登录…' : '登录' }}</button>
      <small>平台只有一个超级管理员；其他后台账号均由超级管理员创建并分配岗位。</small>
    </form>
  </div>
  <div v-else class="shell">
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark" aria-hidden="true"><svg viewBox="0 0 24 24"><path d="M13.3 2 5.7 13h5.1L9.9 22l8.4-12h-5.2z" /></svg></span><span>充电运营平台</span></div>
      <nav aria-label="管理功能"><button v-for="item in visiblePages" :key="item" class="nav-item" :class="{ active: page === item }" :aria-current="page === item ? 'page' : undefined" @click="load(item)">{{ titles[item][1] }}</button></nav>
      <div class="environment">
        <label for="tenant-switcher">当前租户</label>
        <select id="tenant-switcher" v-model="currentTenantId" :disabled="!(sessionContext?.tenants.length)" :aria-label="`当前租户：${currentTenant?.displayName ?? '尚未开通'}`" @change="switchTenant">
          <option v-if="!sessionContext?.tenants.length" value="">尚未开通租户</option>
          <option v-for="tenant in sessionContext?.tenants ?? []" :key="tenant.id" :value="tenant.id">{{ tenant.displayName }}</option>
        </select>
        <span v-if="sessionContext?.platformAdministrator">平台总管理员</span>
        <span>租户隔离 · 全操作审计</span>
        <button class="link-button" @click="signOut">退出登录</button>
      </div>
    </aside>
    <main id="main-content" class="content" tabindex="-1">
      <header><div><p class="eyebrow">{{ titles[page][0] }}</p><h1>{{ titles[page][1] }}</h1></div><button v-if="page !== 'security'" class="refresh" :disabled="loading" @click="load()">刷新数据</button></header>
      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
      <el-alert v-if="notice" :title="notice" type="success" :closable="false" show-icon />
      <section v-if="temporaryCredential" class="credential-notice" role="status">
        <div><strong>一次性临时凭据</strong><span>仅本次显示。对方首次登录后必须修改密码，请通过安全渠道单独交付。</span></div>
        <code>{{ temporaryCredential.username }} / {{ temporaryCredential.password }}</code>
        <button @click="copyTemporaryCredential">复制凭据</button><button class="secondary-button" @click="temporaryCredential = null">已安全保存</button>
      </section>
      <div v-loading="loading">
        <template v-if="page === 'platform'">
          <section class="platform-intro">
            <div><p class="eyebrow">CONTROL PLANE</p><h2>平台运营方</h2><span>你位于所有租户之上，负责开通下游运营商、分配后台账号、暂停服务和查看全局规模。</span></div>
            <dl><div><dt>平台角色</dt><dd>平台总管理员</dd></div><div><dt>管理边界</dt><dd>跨租户控制面</dd></div><div><dt>账号策略</dt><dd>管理员分配 · 首次改密</dd></div></dl>
          </section>
          <section class="metrics platform-metrics" aria-label="平台经营概览">
            <article><span>下游租户</span><strong>{{ platformOverview.activeTenants }}<small>/ {{ platformOverview.totalTenants }}</small></strong><em>正常运营 / 全部租户</em></article>
            <article><span>场站与设备</span><strong>{{ platformOverview.stations }}<small>/ {{ platformOverview.devices }}</small></strong><em>{{ platformOverview.onlineDevices }} 台设备在线</em></article>
            <article><span>今日净收</span><strong>{{ money(platformOverview.todayNetRevenueMinor) }}</strong><em>{{ platformOverview.successfulPaymentsToday }} 笔成功支付，已扣今日退款</em></article>
            <article class="revenue"><span>已确认平台服务费</span><strong>{{ money(platformOverview.confirmedPlatformServiceFeeMinor) }}</strong><em>其中 {{ money(platformOverview.pendingPlatformServiceFeeMinor) }} 待结清</em></article>
          </section>
          <form class="panel form tenant-onboarding" @submit.prevent="createTenant">
            <div class="form-heading"><div><p class="eyebrow">ONBOARDING</p><h2>开通下游租户</h2></div><span>创建后在“账号与权限”中为该租户分配管理员或员工。</span></div>
            <label>租户编码<input v-model="tenantForm.code" pattern="[a-z0-9][a-z0-9-]{1,62}" autocomplete="off" placeholder="例如 east-region" required></label>
            <label>运营商名称<input v-model="tenantForm.displayName" autocomplete="organization" placeholder="企业或运营商名称" required></label>
            <button :disabled="loading">{{ loading ? '正在创建…' : '创建租户' }}</button>
          </form>
          <section class="panel table-panel"><h2>下游租户</h2><div class="table-scroll"><table class="platform-tenant-table"><thead><tr><th>租户</th><th>编码</th><th>活跃成员</th><th>场站 / 设备</th><th>设备在线</th><th>今日支付 / 净收</th><th>已确认服务费</th><th>状态</th><th>创建时间</th><th>操作</th></tr></thead><tbody><tr v-for="row in platformTenants" :key="String(row.id)"><td><strong>{{ row.displayName }}</strong></td><td class="mono">{{ row.code }}</td><td>{{ row.activeMembers }}</td><td>{{ row.stations }} / {{ row.devices }}</td><td>{{ row.onlineDevices }} / {{ row.devices }}</td><td>{{ row.successfulPaymentsToday }} 笔<small>{{ money(row.todayNetRevenueMinor) }}</small></td><td>{{ money(row.confirmedPlatformServiceFeeMinor) }}<small>待结 {{ money(row.pendingPlatformServiceFeeMinor) }}</small></td><td><span class="state" :class="{ 'state-muted': row.status !== 'ACTIVE' }">{{ row.status }}</span></td><td>{{ date(row.createdAt) }}</td><td><button v-if="row.status === 'ACTIVE'" class="danger-button" @click="changeTenantStatus(row, 'SUSPENDED')">暂停服务</button><button v-else-if="row.status === 'SUSPENDED'" @click="changeTenantStatus(row, 'ACTIVE')">恢复服务</button></td></tr><tr v-if="!platformTenants.length"><td colspan="10" class="empty-cell">尚未开通任何下游租户</td></tr></tbody></table></div></section>
        </template>
        <template v-else-if="page === 'dashboard'">
          <section class="metrics"><article><span>设备在线</span><strong>{{ summary.onlineDevices }}<small>/ {{ summary.totalDevices }}</small></strong><em>{{ onlineRate }}%</em></article><article><span>可用充电位</span><strong>{{ summary.availableConnectors }}</strong><em>当前可启动</em></article><article><span>进行中订单</span><strong>{{ summary.activeOrders }}</strong><em>实时设备事件驱动</em></article><article class="revenue"><span>今日净收</span><strong>{{ revenue }}</strong><em>成功支付减成功退款</em></article></section>
          <section class="grid"><article class="panel wide"><div class="panel-title"><div><p>PLATFORM</p><h2>生产状态</h2></div></div><div class="status-board"><b>双层租户隔离</b><span>JWT 租户授权 + PostgreSQL RLS</span><b>可靠设备链路</b><span>mTLS、HMAC、nonce、JetStream</span><b>交易一致性</b><span>幂等、行锁、outbox、双式账务</span></div></article><article class="panel"><div class="panel-title"><div><p>CHECKLIST</p><h2>上线门禁</h2></div></div><ul class="tasks"><li><span>支付对账</span><b>强制</b></li><li><span>备份恢复</span><b>强制</b></li><li><span>压力测试</span><b>强制</b></li></ul></article></section>
        </template>
        <template v-else-if="page === 'organization' && organizationTree">
          <section class="hierarchy-summary" aria-label="渠道组织总览">
            <article><span>合作组织</span><strong>{{ organizationTree.children.length }}</strong></article>
            <article><span>场站</span><strong>{{ organizationTree.totals.stationCount }}</strong></article>
            <article><span>充电桩 / 端口</span><strong>{{ organizationTree.totals.deviceCount }} / {{ organizationTree.totals.connectorCount }}</strong></article>
            <article><span>终端充电用户</span><strong>{{ organizationTree.totals.customerCount }}</strong></article>
          </section>
          <section class="organization-flow">
            <article class="organization-card platform-card"><p>PLATFORM</p><h2>平台运营方</h2><span>平台总管理员 · 跨租户运营与服务费结算</span></article>
            <div class="flow-connector" aria-hidden="true"></div>
            <article class="organization-card root-card"><div><span class="level-badge">一级</span><span class="state">{{ organizationTree.root.status }}</span></div><h2>{{ organizationTree.root.name }}</h2><p>{{ organizationType(organizationTree.root.organizationType) }} · {{ organizationTree.root.code }}</p><div class="organization-stats"><span>自营场站 <b>{{ organizationTree.root.stationCount }}</b></span><span>设备 <b>{{ organizationTree.root.deviceCount }}</b></span><span>端口 <b>{{ organizationTree.root.connectorCount }}</b></span><span>用户 <b>{{ organizationTree.root.customerCount }}</b></span></div></article>
            <div class="flow-connector" aria-hidden="true"></div>
            <div v-if="organizationTree.children.length" class="child-organizations">
              <article v-for="row in organizationTree.children" :key="row.id" class="organization-card child-card">
                <div><span class="level-badge">{{ row.hierarchyLevel === 2 ? '二级' : '三级' }}</span><span class="state">{{ row.status }}</span></div><h3>{{ row.name }}</h3><p>{{ organizationType(row.organizationType) }} · {{ row.code }}</p><small>上级：{{ organizations.find(item => item.id === row.parentId)?.name || '一级组织' }}</small><small>{{ row.contactName || '未设置联系人' }}<template v-if="row.contactMobile"> · {{ row.contactMobile }}</template></small><div class="organization-stats"><span>场站 <b>{{ row.stationCount }}</b></span><span>设备 <b>{{ row.deviceCount }}</b></span><span>端口 <b>{{ row.connectorCount }}</b></span><span>用户 <b>{{ row.customerCount }}</b></span></div><div class="card-actions"><button v-if="row.status === 'ACTIVE'" class="danger-button" @click="changeOrganizationStatus(row, 'SUSPENDED')">暂停</button><button v-else-if="row.status === 'SUSPENDED'" @click="changeOrganizationStatus(row, 'ACTIVE')">恢复</button><button v-if="row.status !== 'CLOSED'" class="secondary-button" @click="changeOrganizationStatus(row, 'CLOSED')">关闭</button></div>
              </article>
            </div>
            <div v-else class="empty-organization">尚未建立下游合作组织；根组织下的场站视为自营场站。</div>
          </section>
          <section class="forms organization-forms"><form class="panel form" @submit.prevent="saveRootOrganization"><h2>一级组织资料</h2><input v-model="rootOrganizationForm.name" placeholder="组织名称" required><select v-model="rootOrganizationForm.organizationType"><option value="REGIONAL_OPERATOR">区域运营商</option><option value="FIRST_TIER_CONTRACTOR">一级分包商</option><option value="DIRECT_BRANCH">直营网点</option></select><input v-model="rootOrganizationForm.contactName" placeholder="联系人"><input v-model="rootOrganizationForm.contactMobile" placeholder="联系电话"><button>保存一级组织</button></form><form class="panel form" @submit.prevent="createOrganization"><h2>新增{{ (selectedOrganizationParent?.hierarchyLevel ?? 1) + 1 }}级合作组织</h2><select v-model="organizationForm.parentId" required @change="alignOrganizationType"><option value="" disabled>选择上级组织</option><option v-for="row in organizationParents" :key="row.id" :value="row.id">{{ row.name }}（{{ row.hierarchyLevel }}级）</option></select><input v-model="organizationForm.code" placeholder="组织编码（大写字母/数字）" required><input v-model="organizationForm.name" placeholder="组织名称" required><select v-model="organizationForm.organizationType"><template v-if="selectedOrganizationParent?.hierarchyLevel === 2"><option value="THIRD_TIER_FRANCHISE">三级加盟商</option><option value="SITE_PARTNER">场地方</option></template><template v-else><option value="SECOND_TIER_PARTNER">二级合作商</option><option value="SITE_PARTNER">场地方</option></template></select><input v-model="organizationForm.contactName" placeholder="联系人"><input v-model="organizationForm.contactMobile" placeholder="联系电话"><button>创建合作组织</button></form></section>
        </template>
        <template v-else-if="page === 'assets'">
          <section class="forms"><form class="panel form" @submit.prevent="createStation"><h2>新增场站</h2><select v-model="stationForm.organizationId" required><option value="" disabled>选择归属组织</option><option v-for="row in organizations.filter(item => item.status === 'ACTIVE')" :key="row.id" :value="row.id">{{ row.name }}（{{ organizationType(row.organizationType) }}）</option></select><input v-model="stationForm.code" placeholder="场站编码" required><input v-model="stationForm.name" placeholder="场站名称" required><input v-model="stationForm.address" placeholder="地址"><select v-model="stationForm.status"><option>DRAFT</option><option>ACTIVE</option></select><button>创建场站</button></form><form class="panel form" @submit.prevent="createDevice"><h2>新增设备</h2><select v-model="deviceForm.stationId" required><option value="" disabled>选择场站</option><option v-for="row in stations" :key="String(row.id)" :value="row.id">{{ row.name }}</option></select><input v-model="deviceForm.deviceCode" placeholder="设备编码" required><input v-model="deviceForm.protocolCode" placeholder="协议编码" required><input v-model="deviceForm.productModel" placeholder="产品型号" required><input v-model.number="deviceForm.connectorCount" type="number" min="1" max="128" placeholder="实际端口数量" required><input v-model.number="deviceForm.ratedPowerW" type="number" min="1" placeholder="单端口额定功率（W）" required><button>创建设备</button></form></section>
          <section class="panel table-panel"><h2>场站</h2><table><thead><tr><th>编码</th><th>名称</th><th>归属组织</th><th>状态</th><th>设备</th><th>端口</th></tr></thead><tbody><tr v-for="row in stations" :key="String(row.id)"><td>{{ row.code }}</td><td>{{ row.name }}</td><td>{{ row.organizationName }}<small>{{ organizationType(row.organizationType) }}</small></td><td><span class="state">{{ row.status }}</span></td><td>{{ row.deviceCount }}</td><td>{{ row.connectorCount }}</td></tr></tbody></table></section>
          <section class="panel table-panel"><h2>设备</h2><table><thead><tr><th>设备编码</th><th>场站</th><th>型号</th><th>协议</th><th>端口</th><th>状态</th><th>最后在线</th><th>凭据</th></tr></thead><tbody><tr v-for="row in devices" :key="String(row.id)"><td>{{ row.deviceCode }}</td><td>{{ row.stationName }}</td><td>{{ row.productModel }}</td><td>{{ row.protocolCode }}</td><td>{{ row.connectorCount }}</td><td><span class="state">{{ row.status }}</span></td><td>{{ date(row.lastSeenAt) }}</td><td><button @click="rotateCredential(row)">轮换密钥</button></td></tr></tbody></table></section>
          <section class="panel table-panel"><h2>充电端口</h2><table><thead><tr><th>设备</th><th>端口</th><th>外部编码</th><th>额定功率</th><th>状态</th><th>费率 ID</th></tr></thead><tbody><tr v-for="row in connectors" :key="String(row.id)"><td>{{ row.deviceCode }}</td><td>{{ row.connectorNo }}</td><td>{{ row.externalCode }}</td><td>{{ row.ratedPowerW }} W</td><td><span class="state">{{ row.status }}</span></td><td class="mono">{{ short(row.tariffId) }}</td></tr></tbody></table></section>
        </template>
        <template v-else-if="page === 'orders'">
          <section class="panel table-panel"><table><thead><tr><th>订单号</th><th>场站/设备</th><th>端口</th><th>客户</th><th>状态</th><th>电量</th><th>应付/已付</th><th>创建时间</th><th>操作</th></tr></thead><tbody><tr v-for="row in orders" :key="String(row.id)"><td>{{ row.orderNo }}</td><td>{{ row.stationName }}<small>{{ row.deviceCode }}</small></td><td>{{ row.connectorNo }}</td><td>{{ row.customerName || '—' }}</td><td><span class="state">{{ row.status }}</span></td><td>{{ (Number(row.energyWh) / 1000).toFixed(2) }} kWh</td><td>{{ money(row.payableAmountMinor) }} / {{ money(row.paidAmountMinor) }}</td><td>{{ date(row.createdAt) }}</td><td><button v-if="['CREATED','START_PENDING'].includes(String(row.status))" class="danger-button" @click="cancelOrder(row)">取消</button></td></tr></tbody></table></section>
        </template>
        <template v-else-if="page === 'tariffs'">
          <form class="panel form horizontal" @submit.prevent="createTariff"><h2>新增费率</h2><input v-model="tariffForm.name" placeholder="费率名称" required><select v-model="tariffForm.billingMode"><option>DURATION</option><option>ENERGY</option><option>HYBRID</option></select><input v-model.number="tariffForm.durationUnitPriceMinor" type="number" min="0" placeholder="每分钟分" required><input v-model.number="tariffForm.energyUnitPriceMinor" type="number" min="0" placeholder="每Wh分" required><input v-model.number="tariffForm.minimumAmountMinor" type="number" min="0" placeholder="最低收费分" required><button>保存草稿</button></form>
          <section class="panel table-panel"><table><thead><tr><th>名称</th><th>模式</th><th>规则</th><th>生效时间</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in tariffs" :key="String(row.id)"><td>{{ row.name }}</td><td>{{ row.billingMode }}</td><td class="mono">{{ short(row.priceRules) }}</td><td>{{ date(row.effectiveFrom) }}</td><td><span class="state">{{ row.status }}</span></td><td><button v-if="row.status === 'DRAFT'" @click="activateTariff(row)">发布</button></td></tr></tbody></table></section>
        </template>
        <template v-else-if="page === 'finance'">
          <section class="access-policy"><strong>支付机构官方逐笔分账</strong><span>消费者向租户直营网商户支付；支付机构按订单将平台服务费和上级加盟分成直接划入已审核商户账户，剩余资金留在直接收款商户。普通个人收款码不参与此链路。</span><em>金额留痕 · 幂等重试 · 退款回退</em></section>
          <section class="forms">
            <form class="panel form" @submit.prevent="createMerchantChannel"><h2>组织直接收款商户通道</h2><select v-model="merchantForm.organizationId" required><option value="" disabled>选择直接收款组织</option><option v-for="row in organizations.filter(item => item.status === 'ACTIVE')" :key="row.id" :value="row.id">{{ row.name }}（{{ row.hierarchyLevel }}级）</option></select><input v-model="merchantForm.merchantId" placeholder="微信支付商户号" required><input v-model="merchantForm.applicationId" placeholder="小程序 AppID" required><input v-model="merchantForm.secretReference" placeholder="env:密钥前缀" required><input v-model="merchantForm.notifyUrl" type="url" placeholder="支付回调：…/payments/WECHAT/租户编码/商户号/callback" required><input v-model="merchantForm.refundNotifyUrl" type="url" placeholder="退款回调：…/refunds/WECHAT/租户编码/商户号/callback" required><label class="checkbox-label"><input v-model="merchantForm.profitSharingRequired" type="checkbox"><span>强制逐笔官方分账（无有效规则时拒绝发起支付）</span></label><button>创建通道</button></form>
            <form class="panel form" @submit.prevent="registerProfitSharingReceiver"><h2>登记分账收款账户</h2><select v-model="profitSharingReceiverForm.merchantChannelId" required><option value="" disabled>选择发起分账的直接收款商户</option><option v-for="row in merchantChannels.filter(item => item.status === 'ACTIVE' && item.channel === 'WECHAT')" :key="String(row.id)" :value="String(row.id)">{{ row.organizationName }} · {{ row.merchantId }}</option></select><select v-model="profitSharingReceiverForm.ownerType" required><option value="ORGANIZATION">加盟/合作组织</option><option v-if="sessionContext?.platformAdministrator" value="PLATFORM">平台服务费账户</option></select><select v-if="profitSharingReceiverForm.ownerType === 'ORGANIZATION'" v-model="profitSharingReceiverForm.organizationId" required><option v-for="row in organizations.filter(item => item.status === 'ACTIVE')" :key="row.id" :value="row.id">{{ row.name }}（{{ row.hierarchyLevel }}级）</option></select><input v-model="profitSharingReceiverForm.receiverAccount" inputmode="numeric" pattern="[0-9]{6,32}" placeholder="经支付机构审核的商户号" required><input v-model="profitSharingReceiverForm.receiverName" placeholder="商户法定名称" required><select v-model="profitSharingReceiverForm.relationType" required><option value="PARTNER">合作伙伴</option><option value="DISTRIBUTOR">分销商</option><option value="HEADQUARTER">总部</option><option value="BRAND">品牌方</option><option value="STORE">门店</option><option value="SERVICE_PROVIDER">服务商</option><option value="CUSTOM">自定义关系</option></select><input v-if="profitSharingReceiverForm.relationType === 'CUSTOM'" v-model="profitSharingReceiverForm.customRelation" maxlength="10" placeholder="关系名称（最多10字）" required><button>向支付机构登记</button></form>
          </section>
          <form class="panel form horizontal" @submit.prevent="createProfitSharingPolicy"><h2>新增逐笔分账规则</h2><select v-model="profitSharingPolicyForm.sourceOrganizationId" required><option v-for="row in organizations.filter(item => item.status === 'ACTIVE')" :key="row.id" :value="row.id">订单归属：{{ row.name }}</option></select><select v-model="profitSharingPolicyForm.receiverId" required><option value="" disabled>选择已审核收款账户</option><option v-for="row in profitSharingReceivers.filter(item => item.status === 'ACTIVE')" :key="String(row.id)" :value="row.id">{{ row.ownerType === 'PLATFORM' ? '平台' : row.organizationName }} · {{ row.receiverName }}（{{ row.receiverAccount }}）</option></select><input v-model.number="profitSharingPolicyForm.basisPoints" type="number" min="1" max="3000" title="100 基点等于 1%，所有接收方合计不得超过支付机构批准上限" placeholder="分账比例（基点）" required><input v-model="profitSharingPolicyForm.effectiveFrom" type="date" required><input v-model="profitSharingPolicyForm.effectiveUntil" type="date"><button>启用规则</button></form>
          <section class="panel table-panel"><h2>分账收款账户</h2><div class="table-scroll"><table><thead><tr><th>直接收款方</th><th>分账归属</th><th>渠道</th><th>接收商户号</th><th>商户名称</th><th>关系</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in profitSharingReceivers" :key="String(row.id)"><td>{{ row.collectingOrganizationName }} · {{ row.collectingMerchantId }}</td><td>{{ row.ownerType === 'PLATFORM' ? '平台' : row.organizationName }}</td><td>{{ row.channel }}</td><td class="mono">{{ row.receiverAccount }}</td><td>{{ row.receiverName }}</td><td>{{ row.customRelation || row.relationType }}</td><td><span class="state">{{ row.status }}</span></td><td><button v-if="row.status === 'ACTIVE'" class="danger-button" @click="disableProfitSharingReceiver(row)">停用</button></td></tr><tr v-if="!profitSharingReceivers.length"><td colspan="8" class="empty-cell">尚未登记任何支付机构分账收款账户</td></tr></tbody></table></div></section>
          <section class="panel table-panel"><h2>逐笔分账规则</h2><div class="table-scroll"><table><thead><tr><th>订单归属组织</th><th>收款方</th><th>渠道</th><th>比例</th><th>生效期</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in profitSharingPolicies" :key="String(row.id)"><td>{{ row.sourceOrganizationName }}</td><td>{{ row.receiverOwnerType === 'PLATFORM' ? '平台 · ' : '' }}{{ row.receiverDisplayName }}</td><td>{{ row.channel }}</td><td>{{ Number(row.basisPoints) / 100 }}%</td><td>{{ row.effectiveFrom }} ~ {{ row.effectiveUntil || '长期' }}</td><td><span class="state">{{ row.status }}</span></td><td><button v-if="row.status === 'ACTIVE'" class="danger-button" @click="disableProfitSharingPolicy(row)">停用</button></td></tr><tr v-if="!profitSharingPolicies.length"><td colspan="7" class="empty-cell">尚未配置逐笔分账规则；强制分账开启时系统将拒绝无规则支付</td></tr></tbody></table></div></section>
          <section class="panel table-panel"><h2>支付机构分账执行记录</h2><div class="table-scroll"><table><thead><tr><th>商户订单</th><th>分账单号</th><th>渠道</th><th>分账金额</th><th>状态</th><th>尝试次数</th><th>支付机构单号/异常</th><th>完成时间</th></tr></thead><tbody><tr v-for="row in profitSharingOrders" :key="String(row.id)"><td>{{ row.merchantOrderNo }}</td><td class="mono">{{ row.outOrderNo }}</td><td>{{ row.channel }}</td><td>{{ money(row.amountMinor) }}</td><td><span class="state" :class="{ 'state-warning': ['FAILED','PARTIAL_FAILED'].includes(String(row.status)) }">{{ row.status }}</span></td><td>{{ row.attempts }}</td><td class="mono">{{ row.providerOrderNo || row.lastError || '—' }}</td><td>{{ date(row.completedAt) }}</td></tr><tr v-if="!profitSharingOrders.length"><td colspan="8" class="empty-cell">还没有真实支付产生的分账记录</td></tr></tbody></table></div></section>
          <section class="panel table-panel"><h2>退款分账回退记录</h2><div class="table-scroll"><table><thead><tr><th>退款 ID</th><th>回退单号</th><th>原接收商户</th><th>回退金额</th><th>状态</th><th>尝试次数</th><th>支付机构单号/异常</th><th>完成时间</th></tr></thead><tbody><tr v-for="row in profitSharingReturns" :key="String(row.id)"><td class="mono">{{ short(row.refundId) }}</td><td class="mono">{{ row.outReturnNo }}</td><td class="mono">{{ row.receiverAccount }}</td><td>{{ money(row.amountMinor) }}</td><td><span class="state" :class="{ 'state-warning': row.status === 'FAILED' }">{{ row.status }}</span></td><td>{{ row.attempts }}</td><td class="mono">{{ row.providerReturnNo || row.lastError || '—' }}</td><td>{{ date(row.completedAt) }}</td></tr><tr v-if="!profitSharingReturns.length"><td colspan="8" class="empty-cell">尚无真实退款产生的分账回退记录</td></tr></tbody></table></div></section>
          <section class="forms"><form class="panel form" @submit.prevent="createRefund"><h2>发起退款</h2><input v-model="refundForm.paymentId" placeholder="支付 ID" required><input v-model.number="refundForm.amountMinor" type="number" min="1" placeholder="退款金额（分）" required><input v-model="refundForm.reason" placeholder="原因" required><button>提交退款并同步处理分账回退</button></form><form class="panel form" @submit.prevent="generateSettlement"><h2>生成内部周期核算单</h2><input v-model="settlementForm.ruleId" placeholder="核算规则 ID" required><input v-model="settlementForm.periodStart" type="date" required><input v-model="settlementForm.periodEnd" type="date" required><button>生成内部核算</button></form></section>
          <form class="panel form horizontal" @submit.prevent="createSettlementRule"><h2>内部周期核算规则（不执行资金转账）</h2><select v-model="settlementRuleForm.organizationId" required><option value="" disabled>选择核算组织</option><option v-for="row in organizations.filter(item => item.status === 'ACTIVE')" :key="row.id" :value="row.id">{{ row.name }}</option></select><input v-model="settlementRuleForm.name" placeholder="规则名称" required><input v-model="settlementRuleForm.beneficiaryCode" placeholder="受益方编码" required><input v-model.number="settlementRuleForm.shareBasisPoints" type="number" min="0" max="10000" placeholder="内部核算比例（基点）" required><input v-model.number="settlementRuleForm.platformServiceFeeBasisPoints" type="number" min="0" max="10000" placeholder="平台服务费基点" required><input v-model.number="settlementRuleForm.fixedServiceFeeMinor" type="number" min="0" placeholder="固定服务费（分）" required><input v-model="settlementRuleForm.effectiveFrom" type="date" required><button>创建内部核算规则</button></form>
          <section class="panel table-panel"><h2>商户通道</h2><table><thead><tr><th>直接收款组织</th><th>渠道</th><th>直接收款商户号</th><th>应用</th><th>官方逐笔分账</th><th>密钥引用</th><th>状态</th></tr></thead><tbody><tr v-for="row in merchantChannels" :key="String(row.id)"><td>{{ row.organizationName }}</td><td>{{ row.channel }}</td><td>{{ row.merchantId }}</td><td>{{ row.applicationId }}</td><td>{{ row.profitSharingRequired ? '强制' : '关闭' }}</td><td class="mono">{{ row.secretReference }}</td><td><span class="state">{{ row.status }}</span></td></tr></tbody></table></section>
          <section class="panel table-panel"><h2>支付流水</h2><table><thead><tr><th>商户订单</th><th>业务订单</th><th>渠道</th><th>金额</th><th>状态</th><th>完成时间</th></tr></thead><tbody><tr v-for="row in payments" :key="String(row.id)"><td>{{ row.merchantOrderNo }}</td><td>{{ row.orderNo }}</td><td>{{ row.channel }}</td><td>{{ money(row.amountMinor) }}</td><td><span class="state">{{ row.status }}</span></td><td>{{ date(row.completedAt) }}</td></tr></tbody></table></section>
          <section class="panel table-panel"><h2>退款与内部周期核算</h2><table><thead><tr><th>类型</th><th>编号</th><th>交易净额</th><th>平台服务费</th><th>核算金额</th><th>状态/操作</th></tr></thead><tbody><tr v-for="row in refunds" :key="String(row.id)"><td>退款</td><td>{{ row.merchantRefundNo }}</td><td>—</td><td>—</td><td>{{ money(row.amountMinor) }}</td><td>{{ row.status }}</td></tr><tr v-for="row in settlements" :key="String(row.id)"><td>内部核算</td><td>{{ row.periodStart }} ~ {{ row.periodEnd }}</td><td>{{ money(row.grossAmountMinor) }}</td><td>{{ money(row.platformServiceFeeMinor) }}</td><td>{{ money(row.settlementAmountMinor) }}</td><td><span>{{ row.status }}</span><button v-if="row.status === 'DRAFT'" @click="transitionSettlement(row, 'CONFIRMED')">确认</button><button v-if="row.status === 'CONFIRMED'" @click="transitionSettlement(row, 'PAYING')">复核中</button><button v-if="row.status === 'PAYING'" @click="transitionSettlement(row, 'PAID')">标记完成</button></td></tr></tbody></table></section>
          <section class="panel table-panel"><h2>内部周期核算规则</h2><table><thead><tr><th>组织</th><th>名称</th><th>受益方</th><th>核算比例</th><th>平台服务费</th><th>生效期</th><th>状态</th></tr></thead><tbody><tr v-for="row in settlementRules" :key="String(row.id)"><td>{{ row.organizationName }}</td><td>{{ row.name }}</td><td>{{ row.beneficiaryCode }}</td><td>{{ Number(row.shareBasisPoints) / 100 }}%</td><td>{{ Number(row.platformServiceFeeBasisPoints) / 100 }}% + {{ money(row.fixedServiceFeeMinor) }}/期</td><td>{{ row.effectiveFrom }} ~ {{ row.effectiveUntil || '长期' }}</td><td>{{ row.status }}</td></tr></tbody></table></section>
          <section class="panel table-panel"><h2>发票申请</h2><table><thead><tr><th>抬头</th><th>订单</th><th>邮箱</th><th>金额</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in invoices" :key="String(row.id)"><td>{{ row.title }}</td><td class="mono">{{ short(row.orderId) }}</td><td>{{ row.email }}</td><td>{{ money(row.amountMinor) }}</td><td>{{ row.status }}</td><td><button v-if="['SUBMITTED','PROCESSING'].includes(String(row.status))" @click="issueInvoice(row)">开票</button><button v-if="['SUBMITTED','PROCESSING'].includes(String(row.status))" @click="rejectInvoice(row)">驳回</button><button v-if="row.status === 'ISSUED'" @click="redIssueInvoice(row)">红冲</button></td></tr></tbody></table></section>
        </template>
        <template v-else-if="page === 'operations'">
          <form class="panel form horizontal" @submit.prevent="createWorkOrder"><h2>新建工单</h2><input v-model="workOrderForm.title" placeholder="工单标题" required><input v-model="workOrderForm.description" placeholder="说明"><select v-model="workOrderForm.priority"><option>LOW</option><option>NORMAL</option><option>HIGH</option><option>URGENT</option></select><input v-model="workOrderForm.assigneeSubject" placeholder="处理人"><button>创建工单</button></form>
          <section class="panel table-panel"><h2>设备告警</h2><table><thead><tr><th>设备</th><th>级别</th><th>告警</th><th>内容</th><th>时间</th><th>状态/操作</th></tr></thead><tbody><tr v-for="row in alarms" :key="String(row.id)"><td>{{ row.deviceCode }}</td><td>{{ row.severity }}</td><td>{{ row.alarmCode }}</td><td>{{ row.message }}</td><td>{{ date(row.occurredAt) }}</td><td><button v-if="row.status === 'OPEN'" @click="acknowledgeAlarm(row)">确认</button><button v-if="row.status !== 'RESOLVED'" @click="resolveAlarm(row)">解决</button><span v-else>{{ row.status }}</span></td></tr></tbody></table></section>
          <section class="panel table-panel"><h2>运维工单</h2><table><thead><tr><th>工单号</th><th>标题</th><th>优先级</th><th>处理人</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in workOrders" :key="String(row.id)"><td>{{ row.workOrderNo }}</td><td>{{ row.title }}</td><td>{{ row.priority }}</td><td>{{ row.assigneeSubject || '—' }}</td><td>{{ row.status }}</td><td><button v-if="row.status === 'OPEN'" @click="transitionWorkOrder(row, 'ASSIGNED')">分配</button><button v-if="['OPEN','ASSIGNED'].includes(String(row.status))" @click="transitionWorkOrder(row, 'IN_PROGRESS')">处理</button><button v-if="row.status === 'IN_PROGRESS'" @click="transitionWorkOrder(row, 'RESOLVED')">解决</button><button v-if="row.status === 'RESOLVED'" @click="transitionWorkOrder(row, 'CLOSED')">关闭</button></td></tr></tbody></table></section>
        </template>
        <template v-else-if="page === 'legal'">
          <form class="panel form horizontal" @submit.prevent="createAgreement"><h2>发布协议版本</h2><select v-model="agreementForm.documentCode"><option>SERVICE_TERMS</option><option>PRIVACY_POLICY</option><option>REFUND_POLICY</option></select><input v-model="agreementForm.version" placeholder="版本号" required><input v-model="agreementForm.title" placeholder="标题" required><input v-model="agreementForm.contentUrl" type="url" placeholder="HTTPS 全文地址" required><input v-model="agreementForm.contentHash" minlength="64" maxlength="64" placeholder="正文 SHA-256" required><input v-model="agreementForm.effectiveAt" type="datetime-local"><button>发布并替换旧版本</button></form>
          <section class="panel table-panel"><table><thead><tr><th>文档</th><th>版本</th><th>标题</th><th>哈希</th><th>生效时间</th><th>状态</th></tr></thead><tbody><tr v-for="row in agreements" :key="String(row.id)"><td>{{ row.documentCode }}</td><td>{{ row.version }}</td><td>{{ row.title }}</td><td class="mono">{{ short(row.contentHash) }}</td><td>{{ date(row.effectiveAt) }}</td><td>{{ row.status }}</td></tr></tbody></table></section>
        </template>
        <template v-else-if="page === 'access'">
          <section class="access-policy"><strong>账号由平台统一管理</strong><span>一个平台超级管理员 · 创建即分配岗位 · 首次登录强制改密 · 停用后立即撤销会话</span><em>内置账号认证</em></section>
          <form class="panel form account-invite" @submit.prevent="createPlatformUser">
            <div class="form-heading"><div><p class="eyebrow">ACCOUNT</p><h2>创建后台账号</h2></div><span>不开放自行注册；一次性临时密码只显示一次。</span></div>
            <label>所属租户<select v-model="membershipForm.tenantId" required><option value="" disabled>请选择租户</option><option v-for="tenant in platformTenants.filter(item => item.status === 'ACTIVE')" :key="String(tenant.id)" :value="String(tenant.id)">{{ tenant.displayName }}</option></select></label>
            <label>登录用户名<input v-model="membershipForm.username" pattern="[A-Za-z0-9][A-Za-z0-9._-]{2,63}" autocomplete="off" required></label>
            <label>姓名<input v-model="membershipForm.displayName" autocomplete="name" required></label>
            <label>邮箱（选填）<input v-model="membershipForm.email" type="email" autocomplete="email"></label>
            <label>岗位角色<select v-model="membershipForm.roleCode"><option value="TENANT_ADMIN">租户管理员</option><option value="OPERATOR">运营</option><option value="FINANCE">财务</option><option value="AUDITOR">审计</option><option value="SUPPORT">客服</option></select></label>
            <button :disabled="loading || !membershipForm.tenantId">{{ loading ? '正在创建…' : '创建账号' }}</button>
          </form>
          <section class="panel table-panel"><h2>后台账号</h2><div class="table-scroll"><table><thead><tr><th>姓名</th><th>账号</th><th>所属租户</th><th>岗位角色</th><th>状态</th><th>最后登录</th><th>操作</th></tr></thead><tbody><tr v-for="row in memberships" :key="`${row.id}-${row.tenantId}`"><td><strong>{{ row.displayName || '—' }}</strong><small>{{ row.email || '未填写邮箱' }}</small></td><td class="mono">{{ row.username }}</td><td>{{ row.tenantName || '未分配' }}</td><td><select v-model="roleDrafts[String(row.id)]" :disabled="loading || row.status !== 'ACTIVE'" aria-label="岗位角色"><option value="TENANT_ADMIN">租户管理员</option><option value="OPERATOR">运营</option><option value="FINANCE">财务</option><option value="AUDITOR">审计</option><option value="SUPPORT">客服</option></select><button :disabled="loading || roleDrafts[String(row.id)] === row.roleCode" @click="changeRole(row)">保存</button></td><td><span class="state" :class="{ 'state-muted': row.status !== 'ACTIVE' }">{{ row.status === 'ACTIVE' ? (row.mustChangePassword ? '待首次改密' : '正常') : '已停用' }}</span><small v-if="row.lockedUntil">锁定至 {{ date(row.lockedUntil) }}</small></td><td>{{ date(row.lastLoginAt) }}</td><td><button :disabled="loading || row.status !== 'ACTIVE'" @click="resetPlatformUserPassword(row)">重置密码</button><button v-if="row.status === 'ACTIVE'" :disabled="loading" class="danger-button" @click="changeMembership(row, 'CLOSED')">停用</button><button v-else :disabled="loading" @click="changeMembership(row, 'ACTIVE')">启用</button></td></tr><tr v-if="!memberships.length"><td colspan="7" class="empty-cell">尚未创建后台账号</td></tr></tbody></table></div></section>
          <section class="panel table-panel"><h2>登录安全事件</h2><div class="table-scroll"><table><thead><tr><th>时间</th><th>账号</th><th>结果</th><th>来源 IP</th><th>原因</th></tr></thead><tbody><tr v-for="row in loginEvents" :key="`${row.occurredAt}-${row.username}-${row.sourceIp}`"><td>{{ date(row.occurredAt) }}</td><td>{{ row.username }}</td><td><span class="state" :class="{ 'state-warning': row.result !== 'SUCCESS' }">{{ row.result }}</span></td><td class="mono">{{ row.sourceIp || '—' }}</td><td>{{ row.failureReason || '—' }}</td></tr><tr v-if="!loginEvents.length"><td colspan="5" class="empty-cell">暂无后台登录事件</td></tr></tbody></table></div></section>
        </template>
        <template v-else-if="page === 'security'">
          <section class="account-summary">
            <div><p class="eyebrow">SIGNED IN AS</p><h2>{{ sessionContext?.displayName || sessionContext?.username }}</h2><span class="mono">{{ sessionContext?.username }}</span></div>
            <dl><div><dt>身份范围</dt><dd>{{ sessionContext?.platformAdministrator ? '平台超级管理员' : `${sessionContext?.tenants.length ?? 0} 个租户` }}</dd></div><div><dt>会话策略</dt><dd>短期令牌 · 可立即撤销</dd></div></dl>
          </section>
          <form class="panel form security-form" @submit.prevent="submitPasswordChange">
            <div class="form-heading"><div><p class="eyebrow">PASSWORD</p><h2>修改登录密码</h2></div><span>保存后当前浏览器会续签，其他设备上的管理会话立即失效。</span></div>
            <label>当前密码<span class="password-field light"><input v-model="passwordForm.currentPassword" :type="showCurrentPassword ? 'text' : 'password'" autocomplete="current-password" minlength="8" maxlength="128" required><button type="button" :aria-pressed="showCurrentPassword" @click="showCurrentPassword = !showCurrentPassword">{{ showCurrentPassword ? '隐藏' : '显示' }}</button></span></label>
            <label>新密码<span class="password-field light"><input v-model="passwordForm.newPassword" :type="showNewPassword ? 'text' : 'password'" autocomplete="new-password" minlength="12" maxlength="128" required><button type="button" :aria-pressed="showNewPassword" @click="showNewPassword = !showNewPassword">{{ showNewPassword ? '隐藏' : '显示' }}</button></span></label>
            <label>确认新密码<span class="password-field light"><input v-model="passwordForm.confirmation" :type="showConfirmationPassword ? 'text' : 'password'" autocomplete="new-password" minlength="12" maxlength="128" required><button type="button" :aria-pressed="showConfirmationPassword" @click="showConfirmationPassword = !showConfirmationPassword">{{ showConfirmationPassword ? '隐藏' : '显示' }}</button></span></label>
            <ul class="password-rules light-rules" aria-live="polite"><li :class="{ passed: passwordRules.length }">12–128 位</li><li :class="{ passed: passwordRules.upper && passwordRules.lower }">包含大小写字母</li><li :class="{ passed: passwordRules.digit }">包含数字</li><li :class="{ passed: passwordRules.symbol }">包含特殊字符</li><li :class="{ passed: passwordForm.confirmation.length > 0 && passwordForm.newPassword === passwordForm.confirmation }">两次输入一致</li></ul>
            <button :disabled="loading || !passwordReady">{{ loading ? '正在更新…' : '更新密码并撤销其他会话' }}</button>
          </form>
        </template>
        <template v-else>
          <section class="panel table-panel"><table><thead><tr><th>时间</th><th>操作人</th><th>动作</th><th>资源</th><th>资源 ID</th></tr></thead><tbody><tr v-for="row in auditRows" :key="String(row.id)"><td>{{ date(row.occurredAt) }}</td><td>{{ row.actorSubject }}</td><td>{{ row.action }}</td><td>{{ row.resourceType }}</td><td class="mono">{{ row.resourceId }}</td></tr></tbody></table></section>
        </template>
      </div>
    </main>
  </div>
</template>
