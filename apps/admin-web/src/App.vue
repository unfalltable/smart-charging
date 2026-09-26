<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { getJson, loadSessionContext, patchJson, postJson, selectTenant,
  type DashboardSummary, type SessionContext } from './api/client'
import { initializeAuth, login, logout } from './auth'

type Page = 'dashboard' | 'assets' | 'orders' | 'tariffs' | 'finance' | 'operations' | 'legal' | 'access' | 'audit'
type Row = Record<string, unknown>

const page = ref<Page>('dashboard')
const loading = ref(false)
const loadError = ref('')
const notice = ref('')
const authenticated = ref(false)
const sessionContext = ref<SessionContext | null>(null)
const currentTenantId = ref('')
const summary = ref<DashboardSummary>({ onlineDevices: 0, totalDevices: 0, availableConnectors: 0, activeOrders: 0, todayRevenueMinor: 0 })
const stations = ref<Row[]>([]), devices = ref<Row[]>([]), connectors = ref<Row[]>([])
const orders = ref<Row[]>([]), tariffs = ref<Row[]>([]), payments = ref<Row[]>([]), refunds = ref<Row[]>([])
const settlements = ref<Row[]>([]), alarms = ref<Row[]>([]), workOrders = ref<Row[]>([]), auditRows = ref<Row[]>([])
const merchantChannels = ref<Row[]>([]), memberships = ref<Row[]>([]), settlementRules = ref<Row[]>([])
const invoices = ref<Row[]>([]), agreements = ref<Row[]>([])

const stationForm = reactive({ code: '', name: '', address: '', timezone: 'Asia/Shanghai', status: 'DRAFT' })
const deviceForm = reactive({ stationId: '', deviceCode: '', protocolCode: '', productModel: '', connectorCount: null as number | null, ratedPowerW: null as number | null })
const tariffForm = reactive({ name: '', billingMode: 'DURATION', durationUnitPriceMinor: null as number | null, energyUnitPriceMinor: null as number | null, minimumAmountMinor: null as number | null })
const workOrderForm = reactive({ title: '', description: '', priority: 'NORMAL', assigneeSubject: '' })
const refundForm = reactive({ paymentId: '', amountMinor: 0, reason: '' })
const settlementForm = reactive({ ruleId: '', periodStart: '', periodEnd: '' })
const merchantForm = reactive({ channel: 'WECHAT', merchantId: '', applicationId: '', secretReference: '', notifyUrl: '', refundNotifyUrl: '', status: 'ACTIVE' })
const membershipForm = reactive({ subject: '', displayName: '', roleCode: 'OPERATOR' })
const settlementRuleForm = reactive({ name: '', beneficiaryCode: '', shareBasisPoints: 0, effectiveFrom: '', effectiveUntil: null as string | null })
const agreementForm = reactive({ documentCode: 'SERVICE_TERMS', version: '', title: '', contentUrl: '', contentHash: '', effectiveAt: '' })

const onlineRate = computed(() => summary.value.totalDevices === 0 ? 0 : Math.round(summary.value.onlineDevices * 100 / summary.value.totalDevices))
const revenue = computed(() => money(summary.value.todayRevenueMinor))
const currentTenant = computed(() => sessionContext.value?.tenants.find((tenant) => tenant.id === currentTenantId.value))
const titles: Record<Page, [string, string]> = {
  dashboard: ['OPERATIONS', '运营总览'], assets: ['ASSETS', '场站设备'], orders: ['ORDERS', '充电订单'],
  tariffs: ['PRICING', '计费策略'], finance: ['FINANCE', '支付与结算'], operations: ['SERVICE', '告警工单'],
  legal: ['LEGAL', '协议与合规'], access: ['ACCESS', '账号与权限'], audit: ['SECURITY', '审计日志']
}

function money(value: unknown) { return `¥ ${(Number(value ?? 0) / 100).toFixed(2)}` }
function date(value: unknown) { return value ? new Date(String(value)).toLocaleString('zh-CN') : '—' }
function short(value: unknown) { const text = String(value ?? '—'); return text.length > 24 ? `${text.slice(0, 21)}…` : text }

async function run(action: () => Promise<void>, success = '操作成功') {
  loadError.value = ''; notice.value = ''; loading.value = true
  try { await action(); notice.value = success }
  catch (error) { loadError.value = error instanceof Error ? error.message : '操作失败' }
  finally { loading.value = false }
}

async function fetchPage(target: Page) {
  if (target === 'dashboard') summary.value = await getJson<DashboardSummary>('/operations/dashboard')
  if (target === 'assets') [stations.value, devices.value, connectors.value] = await Promise.all([
    getJson<Row[]>('/admin/assets/stations'), getJson<Row[]>('/admin/assets/devices'), getJson<Row[]>('/admin/assets/connectors')])
  if (target === 'orders') orders.value = await getJson<Row[]>('/admin/operations/orders')
  if (target === 'tariffs') tariffs.value = await getJson<Row[]>('/admin/tariffs')
  if (target === 'finance') [payments.value, refunds.value, settlements.value, merchantChannels.value, settlementRules.value, invoices.value] = await Promise.all([
    getJson<Row[]>('/admin/finance/payments'), getJson<Row[]>('/admin/finance/refunds'), getJson<Row[]>('/admin/finance/settlements'), getJson<Row[]>('/admin/finance/merchant-channels'), getJson<Row[]>('/admin/finance/settlement-rules'), getJson<Row[]>('/admin/finance/invoices')])
  if (target === 'operations') [alarms.value, workOrders.value] = await Promise.all([
    getJson<Row[]>('/admin/operations/alarms'), getJson<Row[]>('/admin/operations/work-orders')])
  if (target === 'legal') agreements.value = await getJson<Row[]>('/admin/legal/agreements')
  if (target === 'access') memberships.value = await getJson<Row[]>('/admin/access/memberships')
  if (target === 'audit') auditRows.value = await getJson<Row[]>('/admin/operations/audit')
}

async function load(target: Page = page.value) {
  page.value = target
  await run(() => fetchPage(target), '')
}
async function refresh(target: Page, action: () => Promise<unknown>, message: string) {
  await run(async () => { await action(); await fetchPage(target) }, message)
}

async function createStation() { await refresh('assets', () => postJson('/admin/assets/stations', stationForm), '场站已创建'); Object.assign(stationForm, { code: '', name: '', address: '', timezone: 'Asia/Shanghai', status: 'DRAFT' }) }
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
async function transitionSettlement(row: Row, status: string) { await refresh('finance', () => postJson(`/admin/finance/settlements/${row.id}/transition`, { status }), '结算状态已更新') }
async function createMerchantChannel() { await refresh('finance', () => postJson('/admin/finance/merchant-channels', merchantForm), '商户通道已创建') }
async function issueInvoice(row: Row) { const invoiceUrl = prompt('请输入电子发票 HTTPS 地址'); if (invoiceUrl) await refresh('finance', () => postJson(`/admin/finance/invoices/${row.id}/issue`, { invoiceUrl }), '发票已开具') }
async function rejectInvoice(row: Row) { const reason = prompt('请输入驳回原因'); if (reason) await refresh('finance', () => postJson(`/admin/finance/invoices/${row.id}/reject`, { reason }), '发票申请已驳回') }
async function redIssueInvoice(row: Row) { const creditNoteUrl = prompt('请输入红字发票 HTTPS 地址'); const reason = creditNoteUrl ? prompt('请输入红冲原因') : null; if (creditNoteUrl && reason) await refresh('finance', () => postJson(`/admin/finance/invoices/${row.id}/red-issue`, { creditNoteUrl, reason }), '红字发票已开具') }
async function createAgreement() { await refresh('legal', () => postJson('/admin/legal/agreements', { ...agreementForm, effectiveAt: agreementForm.effectiveAt ? new Date(agreementForm.effectiveAt).toISOString() : new Date().toISOString() }), '协议版本已发布'); agreementForm.version = ''; agreementForm.title = ''; agreementForm.contentUrl = ''; agreementForm.contentHash = '' }
async function createMembership() { await refresh('access', () => postJson('/admin/access/memberships', membershipForm), '成员权限已授予'); membershipForm.subject = ''; membershipForm.displayName = '' }
async function changeMembership(row: Row, status: string) { await refresh('access', () => patchJson('/admin/access/memberships/status', { membershipId: row.id, status }), '成员权限已更新') }
async function beginLogin() { try { await login() } catch (error) { loadError.value = error instanceof Error ? error.message : '登录失败' } }
function signOut() { logout(); authenticated.value = false }
async function switchTenant() {
  if (!sessionContext.value) return
  selectTenant(currentTenantId.value, sessionContext.value)
  await load(page.value)
}

onMounted(async () => {
  try {
    authenticated.value = await initializeAuth()
    if (authenticated.value) {
      sessionContext.value = await loadSessionContext()
      currentTenantId.value = sessionStorage.getItem('tenant_id') ?? ''
      await load()
    }
  }
  catch (error) { loadError.value = error instanceof Error ? error.message : '登录失败' }
})
</script>

<template>
  <div v-if="!authenticated" class="login-screen"><div class="login-card"><span class="brand-mark">⚡</span><p>SMART CHARGING</p><h1>充电运营平台</h1><span>使用企业身份登录，权限按租户与岗位双重校验。</span><el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon /><button @click="beginLogin">企业账号登录</button></div></div>
  <div v-else class="shell">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark">⚡</span><span>充电运营平台</span></div>
      <nav><button v-for="item in (Object.keys(titles) as Page[])" :key="item" class="nav-item" :class="{ active: page === item }" @click="load(item)">{{ titles[item][1] }}</button></nav>
      <div class="environment">
        <label for="tenant-switcher">当前租户</label>
        <select id="tenant-switcher" v-model="currentTenantId" :aria-label="`当前租户：${currentTenant?.displayName ?? ''}`" @change="switchTenant">
          <option v-for="tenant in sessionContext?.tenants ?? []" :key="tenant.id" :value="tenant.id">{{ tenant.displayName }}</option>
        </select>
        <span v-if="sessionContext?.platformAdministrator">平台总管理员</span>
        <span>租户隔离 · 全操作审计</span>
        <button class="link-button" @click="signOut">退出登录</button>
      </div>
    </aside>
    <main class="content">
      <header><div><p class="eyebrow">{{ titles[page][0] }}</p><h1>{{ titles[page][1] }}</h1></div><button class="refresh" @click="load()">刷新数据</button></header>
      <el-alert v-if="loadError" :title="loadError" type="error" :closable="false" show-icon />
      <el-alert v-if="notice" :title="notice" type="success" :closable="false" show-icon />
      <div v-loading="loading">
        <template v-if="page === 'dashboard'">
          <section class="metrics"><article><span>设备在线</span><strong>{{ summary.onlineDevices }}<small>/ {{ summary.totalDevices }}</small></strong><em>{{ onlineRate }}%</em></article><article><span>可用充电位</span><strong>{{ summary.availableConnectors }}</strong><em>当前可启动</em></article><article><span>进行中订单</span><strong>{{ summary.activeOrders }}</strong><em>实时设备事件驱动</em></article><article class="revenue"><span>今日实收</span><strong>{{ revenue }}</strong><em>以支付对账为准</em></article></section>
          <section class="grid"><article class="panel wide"><div class="panel-title"><div><p>PLATFORM</p><h2>生产状态</h2></div></div><div class="status-board"><b>双层租户隔离</b><span>JWT 租户授权 + PostgreSQL RLS</span><b>可靠设备链路</b><span>mTLS、HMAC、nonce、JetStream</span><b>交易一致性</b><span>幂等、行锁、outbox、双式账务</span></div></article><article class="panel"><div class="panel-title"><div><p>CHECKLIST</p><h2>上线门禁</h2></div></div><ul class="tasks"><li><span>支付对账</span><b>强制</b></li><li><span>备份恢复</span><b>强制</b></li><li><span>压力测试</span><b>强制</b></li></ul></article></section>
        </template>
        <template v-else-if="page === 'assets'">
          <section class="forms"><form class="panel form" @submit.prevent="createStation"><h2>新增场站</h2><input v-model="stationForm.code" placeholder="场站编码" required><input v-model="stationForm.name" placeholder="场站名称" required><input v-model="stationForm.address" placeholder="地址"><select v-model="stationForm.status"><option>DRAFT</option><option>ACTIVE</option></select><button>创建场站</button></form><form class="panel form" @submit.prevent="createDevice"><h2>新增设备</h2><select v-model="deviceForm.stationId" required><option value="" disabled>选择场站</option><option v-for="row in stations" :key="String(row.id)" :value="row.id">{{ row.name }}</option></select><input v-model="deviceForm.deviceCode" placeholder="设备编码" required><input v-model="deviceForm.protocolCode" placeholder="协议编码" required><input v-model="deviceForm.productModel" placeholder="产品型号" required><input v-model.number="deviceForm.connectorCount" type="number" min="1" max="128" placeholder="实际端口数量" required><input v-model.number="deviceForm.ratedPowerW" type="number" min="1" placeholder="单端口额定功率（W）" required><button>创建设备</button></form></section>
          <section class="panel table-panel"><h2>场站</h2><table><thead><tr><th>编码</th><th>名称</th><th>状态</th><th>设备</th><th>端口</th></tr></thead><tbody><tr v-for="row in stations" :key="String(row.id)"><td>{{ row.code }}</td><td>{{ row.name }}</td><td><span class="state">{{ row.status }}</span></td><td>{{ row.deviceCount }}</td><td>{{ row.connectorCount }}</td></tr></tbody></table></section>
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
          <section class="forms"><form class="panel form" @submit.prevent="createRefund"><h2>发起退款</h2><input v-model="refundForm.paymentId" placeholder="支付 ID" required><input v-model.number="refundForm.amountMinor" type="number" min="1" placeholder="退款金额（分）" required><input v-model="refundForm.reason" placeholder="原因" required><button>提交退款</button></form><form class="panel form" @submit.prevent="generateSettlement"><h2>生成结算单</h2><input v-model="settlementForm.ruleId" placeholder="结算规则 ID" required><input v-model="settlementForm.periodStart" type="date" required><input v-model="settlementForm.periodEnd" type="date" required><button>生成结算</button></form><form class="panel form" @submit.prevent="createMerchantChannel"><h2>微信支付通道</h2><input v-model="merchantForm.merchantId" placeholder="商户号" required><input v-model="merchantForm.applicationId" placeholder="小程序 AppID" required><input v-model="merchantForm.secretReference" placeholder="env:密钥前缀" required><input v-model="merchantForm.notifyUrl" type="url" placeholder="支付 HTTPS 回调地址" required><input v-model="merchantForm.refundNotifyUrl" type="url" placeholder="退款 HTTPS 回调地址" required><button>创建通道</button></form></section>
          <form class="panel form horizontal" @submit.prevent="createSettlementRule"><h2>新增结算规则</h2><input v-model="settlementRuleForm.name" placeholder="规则名称" required><input v-model="settlementRuleForm.beneficiaryCode" placeholder="受益方编码" required><input v-model.number="settlementRuleForm.shareBasisPoints" type="number" min="0" max="10000" placeholder="分成基点" required><input v-model="settlementRuleForm.effectiveFrom" type="date" required><button>创建规则</button></form>
          <section class="panel table-panel"><h2>商户通道</h2><table><thead><tr><th>渠道</th><th>商户号</th><th>应用</th><th>密钥引用</th><th>状态</th></tr></thead><tbody><tr v-for="row in merchantChannels" :key="String(row.id)"><td>{{ row.channel }}</td><td>{{ row.merchantId }}</td><td>{{ row.applicationId }}</td><td class="mono">{{ row.secretReference }}</td><td><span class="state">{{ row.status }}</span></td></tr></tbody></table></section>
          <section class="panel table-panel"><h2>支付流水</h2><table><thead><tr><th>商户订单</th><th>业务订单</th><th>渠道</th><th>金额</th><th>状态</th><th>完成时间</th></tr></thead><tbody><tr v-for="row in payments" :key="String(row.id)"><td>{{ row.merchantOrderNo }}</td><td>{{ row.orderNo }}</td><td>{{ row.channel }}</td><td>{{ money(row.amountMinor) }}</td><td><span class="state">{{ row.status }}</span></td><td>{{ date(row.completedAt) }}</td></tr></tbody></table></section>
          <section class="panel table-panel"><h2>退款与结算</h2><table><thead><tr><th>类型</th><th>编号</th><th>金额</th><th>状态/操作</th></tr></thead><tbody><tr v-for="row in refunds" :key="String(row.id)"><td>退款</td><td>{{ row.merchantRefundNo }}</td><td>{{ money(row.amountMinor) }}</td><td>{{ row.status }}</td></tr><tr v-for="row in settlements" :key="String(row.id)"><td>结算</td><td>{{ row.periodStart }} ~ {{ row.periodEnd }}</td><td>{{ money(row.settlementAmountMinor) }}</td><td><span>{{ row.status }}</span><button v-if="row.status === 'DRAFT'" @click="transitionSettlement(row, 'CONFIRMED')">确认</button><button v-if="row.status === 'CONFIRMED'" @click="transitionSettlement(row, 'PAYING')">付款中</button><button v-if="row.status === 'PAYING'" @click="transitionSettlement(row, 'PAID')">标记已付</button></td></tr></tbody></table></section>
          <section class="panel table-panel"><h2>结算规则</h2><table><thead><tr><th>名称</th><th>受益方</th><th>比例</th><th>生效期</th><th>状态</th></tr></thead><tbody><tr v-for="row in settlementRules" :key="String(row.id)"><td>{{ row.name }}</td><td>{{ row.beneficiaryCode }}</td><td>{{ Number(row.shareBasisPoints) / 100 }}%</td><td>{{ row.effectiveFrom }} ~ {{ row.effectiveUntil || '长期' }}</td><td>{{ row.status }}</td></tr></tbody></table></section>
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
          <form class="panel form horizontal" @submit.prevent="createMembership"><h2>授予租户角色</h2><input v-model="membershipForm.subject" placeholder="OIDC subject" required><input v-model="membershipForm.displayName" placeholder="姓名"><select v-model="membershipForm.roleCode"><option>TENANT_ADMIN</option><option>OPERATOR</option><option>FINANCE</option><option>AUDITOR</option><option>SUPPORT</option></select><button>授权</button></form>
          <section class="panel table-panel"><table><thead><tr><th>姓名</th><th>身份 Subject</th><th>角色</th><th>用户状态</th><th>成员状态</th><th>操作</th></tr></thead><tbody><tr v-for="row in memberships" :key="String(row.id)"><td>{{ row.displayName || '—' }}</td><td class="mono">{{ row.subject }}</td><td>{{ row.roleCode }}</td><td>{{ row.userStatus }}</td><td><span class="state">{{ row.membershipStatus }}</span></td><td><button v-if="row.membershipStatus === 'ACTIVE'" class="danger-button" @click="changeMembership(row, 'DISABLED')">停用</button><button v-else @click="changeMembership(row, 'ACTIVE')">启用</button></td></tr></tbody></table></section>
        </template>
        <template v-else>
          <section class="panel table-panel"><table><thead><tr><th>时间</th><th>操作人</th><th>动作</th><th>资源</th><th>资源 ID</th></tr></thead><tbody><tr v-for="row in auditRows" :key="String(row.id)"><td>{{ date(row.occurredAt) }}</td><td>{{ row.actorSubject }}</td><td>{{ row.action }}</td><td>{{ row.resourceType }}</td><td class="mono">{{ row.resourceId }}</td></tr></tbody></table></section>
        </template>
      </div>
    </main>
  </div>
</template>
