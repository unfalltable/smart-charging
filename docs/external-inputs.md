# 外部材料与联调边界

资质和真实凭据可后补；未启用的外部能力不会阻止管理后台运行。以下材料决定微信正式发布、真实资金交易和真实设备验收，代码和自动检查不能代替这些外部验收：

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

- 唯一平台超级管理员的生产用户名、显示名称和受控初始密码交付方式。
- 用户协议、隐私政策、退款规则、客服电话、发票主体、数据保留期限及等保/隐私评审结论。

## 生产基础设施

- 首批可使用一台受控服务器：Docker Engine、Compose v2、Git、运维脚本使用的 PowerShell 7。单机的数据库、Valkey、NATS 和业务服务已包含在 Compose，但单机停机需要接受维护窗口和机器故障风险。
- 正式 DNS 主机名、服务器公网地址、80/443 入站与证书机构访问条件；设置 `.env` 的 `PUBLIC_HOST` 后 Caddy 自动申请与续期证书，不需要手写反向代理配置。
- 备份目录、加密异地副本位置、恢复时间目标与告警接收渠道。已提供停机一致性卷备份/恢复脚本，正式上线前必须从一份快照恢复到隔离环境验证。
- 扩容为多机后再补 PostgreSQL、Valkey 和 NATS 的高可用连接信息及故障切换部署，不能用单机默认配置承诺多机高可用。

支付宝属于后续软件开发阶段：当前尚未交付小程序、登录或支付退款适配，仅补充 AppID、应用私钥/平台公钥和商户资质不会启用该能力。微信首发不需要先提供这些材料。

钱包线上充值/余额支付/提现、自动税务开票、支付机构退款账单自动下载、押金或免密扣款也尚未实现。现有钱包查询/人工更正、发票申请/凭证录入、完整销售账单导入和后付费欠费拦截不能代替这些链路。软件缺口与上线验收边界见 [生产商用门禁](production-readiness.md)。

平台超级管理员在“平台与租户”开通真实租户，再在“账号与权限”创建后台账号并分配岗位。系统不提供匿名初始化后门；账号创建、停用、重置密码和角色变更全部记录审计日志。
