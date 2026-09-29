# 外部材料与联调边界

代码侧已经完成能够在没有真实资质和桩机时完成的生产功能。以下内容必须由项目方或供应商提供，仓库不会伪造：

## 微信首发

- 已认证微信小程序的 AppID、AppSecret、合法请求域名与隐私协议版本。
- 已登录该 AppID 的微信开发者工具管理员或开发者账号；若工具不在默认安装目录，提供 `cli.bat` 的本机路径。
- 公网可信 HTTPS API 地址（以 `/api/v1` 结尾）及 request 合法域名；协议和电子发票页面域名还需加入业务域名。
- 微信支付商户号、商户 API 私钥及序列号、APIv3 密钥、微信支付公钥及公钥 ID。
- 已开通微信支付分账能力及支付机构批准的最大分账比例；平台、加盟商和合作方的已审核商户号、法定名称、与直接收款商户的真实关系。系统生产链路只接受商户号接收方，不接受个人 OpenID 或普通个人收款码。
- 支付与退款 HTTPS 回调域名；每个组织商户通道的数据库回调地址分别使用
  `/api/v1/public/payments/WECHAT/{tenantCode}/{merchantId}/callback` 和
  `/api/v1/public/refunds/WECHAT/{tenantCode}/{merchantId}/callback`，服务端据此选择该商户独立的验签和解密密钥。
- 订阅消息模板编号和字段名。代码已包含用户授权、access token 缓存、模板字段映射、失败重试和发送器；
  未取得模板资质时保持 `WECHAT_NOTIFICATION_ENABLED=false`，通知 outbox 会保留。

每个需要直接收款的场站归属组织，以及每一个分账接收方，都必须先完成微信支付侧的签约/审核。把支付机构批准的比例上限写入 `PAYMENT_PROFIT_SHARING_MAX_BASIS_POINTS`，先给组织配置直接收款通道，再针对该通道登记上游接收方并配置比例。该配置只约束资金路由；合同、发票和税务处理仍需按各经营主体实际业务确定。

取得模板后，将小程序端的 `notificationTemplateIds` 填入模板 ID，并把服务端映射配置为类似：

```json
{
  "PAYMENT_SUCCEEDED": {
    "templateId": "模板ID",
    "page": "pages/orders/index",
    "bindings": {"character_string1": "orderId", "amount2": "amountMinor"}
  },
  "REFUND_SUCCEEDED": {
    "templateId": "模板ID",
    "page": "pages/orders/index",
    "bindings": {"character_string1": "refundId", "amount2": "amountMinor"}
  }
}
```

压缩成单行后写入 `WECHAT_NOTIFICATION_TEMPLATES_JSON`，再启用 `WECHAT_NOTIFICATION_ENABLED`。

## 第一台 12 口充电桩

- 厂家协议文档、设备唯一编码、每个端口编号、心跳/启动/停止/计量/告警报文样例。
- 设备是否支持 TLS 客户端证书、断网缓存、远程升级和硬件急停。
- 至少一台可反复断电和弱网测试的样机。接入时只新增协议适配器，不改订单与账务域。

## 企业后台与合规

- OIDC 身份平台的授权端点、令牌端点、Issuer、前端 Client ID，以及管理员 MFA 策略。
- 首个管理员的 OIDC `sub`，用于初始化 `TENANT_ADMIN` 成员；系统会阻止停用最后一个管理员。
- 用户协议、隐私政策、退款规则、客服电话、发票主体、数据保留期限及等保/隐私评审结论。

## 生产基础设施（本阶段暂不代配机器）

- PostgreSQL、Valkey、NATS JetStream 的高可用连接信息和独立运行账号。
- 正式域名、TLS 证书、对象存储/CDN、监控告警接收渠道、KMS/秘密管理系统。
- 数据库备份目标和恢复时间目标。正式上线前必须做一次从备份恢复到隔离环境的演练。

支付宝属于第二渠道阶段，需要支付宝小程序 AppID、应用私钥/平台公钥、商户能力与模板消息资质；微信首发不需要先提供。

自托管身份模式由显式 `platform_admin` 在“平台与租户”开通首个租户和管理员；外部 OIDC 的自动化部署也可由具有 `SCOPE_internal` 的服务身份开通。两种方式都不提供匿名初始化后门：

```powershell
$env:INTERNAL_PROVISIONING_TOKEN = Read-Host 'OIDC provisioning token'
.\ops\provision-tenant.ps1 -TenantCode $tenantCode -TenantDisplayName $tenantName `
  -AdminSubject $oidcSubject -AdminDisplayName $adminName
Remove-Item Env:INTERNAL_PROVISIONING_TOKEN
```

开通接口需要真实参数，在一个事务中写入租户、首个管理员成员和审计日志。自托管模式还会创建身份账号、首登改密和 MFA 要求；完成后日常成员与角色变更全部走后台权限管理和审计日志。
