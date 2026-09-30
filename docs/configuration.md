# 统一配置管理

部署配置只有一个入口：根目录下 Git 忽略的 `.env`。仓库提供完整字段模板 `.env.example`，但不会在模板中放入真实秘密。不要在 Java、管理端或小程序源码里写环境地址、租户、账号或秘密。

## 常用命令

```powershell
.\config-manager.cmd init
.\config-manager.cmd status
.\config-manager.cmd validate
.\config-manager.cmd export-miniapp
.\config-manager.cmd credentials
```

- `init`：创建或无损补全根目录 `.env`，密码和主密钥使用密码学安全随机数生成。
- 配置修改：直接用文本编辑器打开 `.env`；不再使用交互式向导。
- `status`：按分类显示配置状态，秘密只显示末四位。
- `validate`：检查基础配置和所有已启用能力；任何错误都会以非零状态退出。
- `export-miniapp`：从统一配置生成 Git 忽略的小程序部署文件。
- `credentials`：仅在明确执行时显示唯一平台超级管理员的初始登录；首次登录后必须改密，原始配置密码不会覆盖已修改的数据库密码。

`docker-start.cmd` 启动前会自动执行同一套校验，不会用假地址、默认账号、模拟支付或测试租户绕过缺失配置。

生成的 `.env` 使用单引号保存字面值，Docker 不会展开密码中的 `$`。手动编辑时保留单引号；需要单引号字符时写成 `\'`。支持 `NAME=value` 和 `NAME='value'`，不支持双引号变量插值或多行值。启动脚本也会把这些值注入当前子进程，避免电脑中同名环境变量覆盖 `.env`。

## 后台账号

管理端固定使用常规用户名和密码，不依赖 Keycloak 或外部 OIDC：

- `PLATFORM_ADMIN_SUBJECT`、`PLATFORM_ADMIN_USERNAME`、`PLATFORM_ADMIN_PASSWORD` 由 `init` 随机生成；
- 管理员密码至少 12 个字符，并含大小写字母、数字及符号；UTF-8 编码不能超过 72 字节（中文字符通常占 3 字节），以符合 bcrypt 的安全边界；
- 平台超级管理员明确保存在 `platform_user`，不属于任何下游租户；
- 首次登录只允许修改临时密码，改密后才签发完整管理权限；
- 密码使用自描述的 bcrypt 散列，连续失败会锁定，访问令牌短期有效，刷新令牌轮换并检测复用；
- 退出、改密、停用或管理员重置密码后，现有管理会话立即失效；
- 只有平台超级管理员可以创建、停用、重置后台账号并分配租户岗位；普通管理员不能继续创建账号；
- 管理后台不开放匿名注册，新账号使用一次性临时密码并在首次登录时强制改密。

## 配置分类

| 分类 | 配置项 | 来源或用途 |
|---|---|---|
| 核心秘密 | `POSTGRES_PASSWORD`、`DATABASE_MIGRATION_PASSWORD`、`DATABASE_RUNTIME_PASSWORD`、`VALKEY_PASSWORD`、`NATS_TOKEN`、`QR_SIGNING_SECRET`、`DEVICE_CREDENTIAL_MASTER_KEY_BASE64`、`AUTH_JWT_SECRET_BASE64` | `init` 自动生成；数据库管理员、迁移、运行密码必须不同，不得提交、共享或写入日志 |
| 后台身份 | `APP_JWT_ISSUER`、`API_JWT_AUDIENCE`、`ALLOWED_ORIGINS` | 平台令牌签发、校验和跨域白名单 |
| 平台管理员 | `PLATFORM_ADMIN_SUBJECT`、`PLATFORM_ADMIN_USERNAME`、`PLATFORM_ADMIN_PASSWORD`、`PLATFORM_ADMIN_DISPLAY_NAME`、`PLATFORM_ADMIN_EMAIL` | 初始化数据库中的唯一平台超级管理员；初始密码只用于首次登录 |
| 账号安全留存 | `IDENTITY_LOGIN_EVENT_RETENTION_DAYS`、`IDENTITY_EXPIRED_TOKEN_RETENTION_DAYS` | 管理员登录事件与过期刷新令牌留存期；到期数据由服务端每日自动清理 |
| 微信身份 | `WECHAT_IDENTITY_ENABLED`、`WECHAT_APP_ID`、`WECHAT_APP_SECRET`、`WECHAT_TENANT_CODE`、`MINIAPP_DEVTOOLS_CLI_PATH` | 有真实小程序资质后启用；CLI 路径仅在未安装到默认目录时填写 |
| 微信通知 | `WECHAT_NOTIFICATION_ENABLED`、`WECHAT_NOTIFICATION_TEMPLATES_JSON` | 通知类型到微信订阅消息模板的映射 |
| 微信支付 | `WECHAT_PAYMENT_ENABLED`、`WECHAT_PAYMENT_DIRECTORY`、`WECHAT_PRIMARY_*`、`PAYMENT_PROFIT_SHARING_MAX_BASIS_POINTS`、`PAYMENT_INTENT_EXPIRE_MINUTES` | 真实商户 APIv3 密钥、只读证书目录、支付机构实际批准的分账比例上限（100 基点 = 1%），以及未支付单超时关单时间（2～120 分钟，默认 30） |
| 设备网关 | `DEVICE_GATEWAY_ENABLED`、`DEVICE_GATEWAY_BIND_ADDRESS`、`DEVICE_TLS_DIRECTORY` | 启用后目录必须包含 `tls.crt`、`tls.key`、`ca.crt` |
| 小程序三环境 | `MINIAPP_DEVELOP_*`、`MINIAPP_TRIAL_*`、`MINIAPP_RELEASE_*` | 以 `/api/v1` 结尾的 HTTPS API、与 `WECHAT_TENANT_CODE` 一致的租户编码和订阅模板 ID |
| 本机端口 | `ADMIN_WEB_PORT`、`CORE_PORT`、`DEVICE_GATEWAY_PORT`、`DEVICE_MANAGEMENT_PORT`、`POSTGRES_PORT`、`VALKEY_PORT`、`NATS_PORT`、`NATS_MONITOR_PORT` | 校验范围和端口冲突 |
| 公网与备份 | `PUBLIC_HOST`、`HTTP_PORT`、`HTTPS_PORT`、`BACKUP_DIRECTORY` | 一个正式 DNS 主机名；80/443 自动 HTTPS 入口；私有一致性备份目录 |
| 性能参数 | `DATABASE_POOL_*`、`ACCESS_TOKEN_MINUTES`、`REFRESH_TOKEN_DAYS`、`RATE_LIMIT_*`、`TRACING_SAMPLE_RATE`、`OUTBOX_PUBLISHER_DELAY_MS`、`APPLICATION_LOG_LEVEL` | Docker 和服务端使用同一份值 |

## 不放在部署文件里的业务配置

租户、员工成员关系、渠道组织、场站、设备、端口、费率、组织直接收款商户通道、分账接收方、逐笔分账规则、订单、支付、退款和内部核算都是生产业务数据，必须通过管理后台或受控接口写入数据库，不能写进 `.env`。仓库及初始化流程不会创建这些数据。每个分账接收方保存的是支付机构已审核的真实商户号和法定名称，不保存二维码图片或个人收款码；每个场站归属组织必须配置自己的有效直接收款通道。

每个加盟商的支付秘密也只编辑根 `.env`。如果后台商户通道引用 `env:FRANCHISE_001`，在该文件追加对应五项：

```env
FRANCHISE_001_PRIVATE_KEY_PATH='/run/secrets/wechat-pay/franchise-001/merchant-private-key.pem'
FRANCHISE_001_MERCHANT_SERIAL='该商户API证书序列号'
FRANCHISE_001_API_V3_KEY='该商户真实32字符APIv3密钥'
FRANCHISE_001_PUBLIC_KEY_PATH='/run/secrets/wechat-pay/franchise-001/wechat-pay-public-key.pem'
FRANCHISE_001_PUBLIC_KEY_ID='该商户微信支付公钥ID'
```

把 PEM 文件放在 `WECHAT_PAYMENT_DIRECTORY/franchise-001/`。验证会要求五项完整、密钥长度正确、文件存在且路径处于只读挂载内。脚本自动生成 Git 忽略的 `runtime-secrets/core-merchant.env`，只向核心容器注入这些商户字段；管理端镜像、小程序和设备网关不会得到加盟商私钥或APIv3密钥。该文件由根 `.env` 重建，不需要你维护第二份配置。

## 生产环境要求

单机 Compose 可以用于首批生产部署，但只有一台机器时存在停机故障域，不能承诺高可用。保持根 `.env` 为唯一人工配置入口；限制文件权限并加密备份。PostgreSQL、Valkey 与 NATS 按容量升级为独立高可用服务时，需要另行调整连接配置和故障切换方案，当前默认 Compose 不会伪装成多机集群。公开域名、小程序 API、支付回调和设备入口必须使用可信 HTTPS/TLS，只有本机回环开发地址允许 HTTP。

`PUBLIC_HOST` 留空时只开放本机回环端口。填写后，启动脚本自动启用 Caddy HTTPS 入口；同时把 `APP_JWT_ISSUER` 和 `ALLOWED_ORIGINS` 都设为 `https://你的主机名`。DNS 指向服务器并开放 80/443 后，Caddy 自动申请、持久保存并续期可信证书。数据库、消息端口与管理诊断端口仍只映射到回环地址。真实桩需要设置设备网关绑定地址并提供厂家支持的 mTLS 材料。

数据库由短任务创建三个独立账号：`charging_app` 只供数据库初始化与角色维护使用；`charging_migrator` 是非超级用户的迁移所有者，为历史跨租户迁移保留 `BYPASSRLS`；业务连接使用 `charging_runtime`，没有超级用户、建库、建角色和 `BYPASSRLS` 权限。旧卷升级会保留数据并迁移业务对象所有者，不需要删除数据重建。

内置数据库账号可以用于生产，但生产发布必须启用 HTTPS、妥善保护 JWT 与数据库秘密、审计管理员操作并完成账号锁定/恢复演练。
