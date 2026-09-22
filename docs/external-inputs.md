# 外部材料与联调边界

代码侧已经完成能够在没有真实资质和桩机时完成的生产功能。以下内容必须由项目方或供应商提供，仓库不会伪造：

## 微信首发

- 已认证微信小程序的 AppID、AppSecret、合法请求域名与隐私协议版本。
- 微信支付商户号、商户 API 私钥及序列号、APIv3 密钥、微信支付公钥及公钥 ID。
- 支付与退款 HTTPS 回调域名；数据库中回调地址分别使用
  `/api/v1/public/payments/WECHAT/{tenantCode}/callback` 和
  `/api/v1/public/refunds/WECHAT/{tenantCode}/callback`。
- 订阅消息模板编号和字段名。代码已包含用户授权、access token 缓存、模板字段映射、失败重试和发送器；
  未取得模板资质时保持 `WECHAT_NOTIFICATION_ENABLED=false`，通知 outbox 会保留。

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

首个管理员只能由数据库迁移账号初始化一次，避免留下公网“创建首个管理员”后门：

```powershell
psql $env:DATABASE_URL -v tenant_code=pilot -v admin_subject=OIDC_SUB -v display_name=初始管理员 `
  -f ops/bootstrap-admin.sql
```

完成后日常成员与角色变更全部走后台权限管理和审计日志。
