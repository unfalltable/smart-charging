# 统一配置管理

部署配置只有一个入口：根目录下 Git 忽略的 `.env.docker`。不要在 Java、管理端或小程序源码里写环境地址、租户、账号或秘密。配置定义、默认值、校验规则和小程序配置导出都由 `ops/configuration.ps1` 维护。

## 常用命令

```powershell
.\config-manager.cmd init
.\config-manager.cmd wizard
.\config-manager.cmd status
.\config-manager.cmd validate
.\config-manager.cmd export-miniapp
.\config-manager.cmd credentials
```

- `init`：创建或无损补全配置，密码和主密钥使用密码学安全随机数生成。
- `wizard`：交互修改真实外部信息；秘密输入不回显，直接回车保留已有值。
- `status`：按分类显示配置状态，秘密只显示末四位。
- `validate`：检查基础配置和所有已启用能力；任何错误都会以非零状态退出。
- `export-miniapp`：从统一配置生成 Git 忽略的小程序部署文件。
- `credentials`：仅在明确执行时显示数据库身份模式的初始平台登录；首次登录后必须改密，原始配置密码不会覆盖已修改的数据库密码。

`docker-start.cmd` 启动前会自动执行同一套校验，不会用假地址、默认账号、模拟支付或测试租户绕过缺失配置。

## 身份模式

默认 `IDENTITY_PROVIDER_MODE=database`，管理端使用常规用户名和密码：

- `PLATFORM_ADMIN_SUBJECT`、`PLATFORM_ADMIN_USERNAME`、`PLATFORM_ADMIN_PASSWORD` 由 `init` 随机生成；
- 平台超级管理员明确保存在 `platform_user`，不属于任何下游租户；
- 首次登录只允许修改临时密码，改密后才签发完整管理权限；
- 密码使用自描述的 bcrypt 散列，连续失败会锁定，访问令牌短期有效，刷新令牌轮换并检测复用；
- 退出、改密、停用或管理员重置密码后，现有管理会话立即失效；
- 平台超级管理员创建租户和首位租户管理员，租户管理员再创建员工账号；管理后台不开放匿名注册。

需要企业统一身份时可在向导中切换到 `IDENTITY_PROVIDER_MODE=external`。此时必须提供：

- `OIDC_ISSUER_URI`：身份平台签发者地址，服务端用它发现公钥并校验令牌；
- `VITE_OIDC_AUTHORIZATION_ENDPOINT`：浏览器授权地址；
- `VITE_OIDC_TOKEN_ENDPOINT`：Authorization Code + PKCE 换取令牌的地址；
- `VITE_OIDC_CLIENT_ID`：管理端 public client 标识，不是 secret；
- `VITE_OIDC_REDIRECT_URI`、`VITE_OIDC_SCOPES`：登录回调和申请范围。

外部模式下账号创建、密码恢复和 MFA 由外部身份平台负责，本系统只保存其真实 subject 与租户岗位关系。

## 配置分类

| 分类 | 配置项 | 来源或用途 |
|---|---|---|
| 核心秘密 | `POSTGRES_PASSWORD`、`VALKEY_PASSWORD`、`QR_SIGNING_SECRET`、`DEVICE_CREDENTIAL_MASTER_KEY_BASE64`、`AUTH_JWT_SECRET_BASE64` | `init` 自动生成；不得提交、共享或写入日志 |
| 身份模式 | `IDENTITY_PROVIDER_MODE`、`APP_JWT_ISSUER`、`API_JWT_AUDIENCE`、`ALLOWED_ORIGINS` | 内置账号或外部 OIDC、平台令牌签发和跨域白名单 |
| 平台管理员 | `PLATFORM_ADMIN_SUBJECT`、`PLATFORM_ADMIN_USERNAME`、`PLATFORM_ADMIN_PASSWORD`、`PLATFORM_ADMIN_DISPLAY_NAME`、`PLATFORM_ADMIN_EMAIL` | 初始化数据库中的唯一平台超级管理员；初始密码只用于首次登录 |
| 外部 OIDC | `OIDC_*`、`VITE_OIDC_*` | 仅在 `external` 模式必填 |
| 账号生命周期 | `IDENTITY_INVITATION_LIFESPAN_HOURS`、`IDENTITY_LOGIN_EVENT_RETENTION_DAYS`、`IDENTITY_EXPIRED_TOKEN_RETENTION_DAYS` | 临时账号有效期、管理员登录事件与过期刷新令牌留存期；到期数据由服务端每日自动清理 |
| 微信身份 | `WECHAT_IDENTITY_ENABLED`、`WECHAT_APP_ID`、`WECHAT_APP_SECRET`、`WECHAT_TENANT_CODE` | 有真实小程序资质后启用 |
| 微信通知 | `WECHAT_NOTIFICATION_ENABLED`、`WECHAT_NOTIFICATION_TEMPLATES_JSON` | 通知类型到微信订阅消息模板的映射 |
| 微信支付 | `WECHAT_PAYMENT_ENABLED`、`WECHAT_PAYMENT_DIRECTORY`、`WECHAT_PRIMARY_*`、`PAYMENT_PROFIT_SHARING_MAX_BASIS_POINTS` | 真实商户 APIv3 密钥、只读证书目录，以及支付机构实际批准的分账比例上限（100 基点 = 1%） |
| 设备网关 | `DEVICE_GATEWAY_ENABLED`、`DEVICE_GATEWAY_BIND_ADDRESS`、`DEVICE_TLS_DIRECTORY` | 启用后目录必须包含 `tls.crt`、`tls.key`、`ca.crt` |
| 小程序三环境 | `MINIAPP_DEVELOP_*`、`MINIAPP_TRIAL_*`、`MINIAPP_RELEASE_*` | HTTPS API、租户编码和订阅模板 ID |
| 本机端口 | `ADMIN_WEB_PORT`、`CORE_PORT`、`DEVICE_GATEWAY_PORT`、`DEVICE_MANAGEMENT_PORT`、`POSTGRES_PORT`、`VALKEY_PORT`、`NATS_PORT`、`NATS_MONITOR_PORT` | 校验范围和端口冲突 |
| 性能参数 | `DATABASE_POOL_*`、`ACCESS_TOKEN_MINUTES`、`REFRESH_TOKEN_DAYS`、`RATE_LIMIT_*`、`TRACING_SAMPLE_RATE`、`OUTBOX_PUBLISHER_DELAY_MS`、`APPLICATION_LOG_LEVEL` | Docker 和服务端使用同一份值 |

## 不放在部署文件里的业务配置

租户、员工成员关系、渠道组织、场站、设备、端口、费率、商户通道、分账接收方、逐笔分账规则、订单、支付、退款和内部核算都是生产业务数据，必须通过管理后台或受控接口写入数据库，不能写进 `.env.docker`。仓库及初始化流程不会创建这些数据。每个分账接收方保存的是支付机构已审核的真实商户号和法定名称，不保存二维码图片或个人收款码。

## 生产环境要求

单机 `.env.docker` 只适合本机验收或受控小规模部署。正式环境应使用云秘密管理、Docker Secret 或编排平台 Secret 注入；PostgreSQL、Valkey 与 NATS 应按容量做高可用、备份恢复和监控。公开域名、小程序 API、支付回调和设备入口必须使用可信 HTTPS/TLS，只有本机回环开发地址允许 HTTP。

内置数据库账号可以继续用于生产，但生产发布必须启用 HTTPS、妥善保护 JWT 与数据库秘密、审计管理员操作并完成账号锁定/恢复演练。组织已有统一身份、需要强制 MFA 或集中离职回收时，再切换外部 OIDC，无需改变业务数据模型。
