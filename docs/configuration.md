# 统一配置管理

部署配置只有一个入口：根目录下 Git 忽略的 `.env.docker`。不要在 Java、管理端或小程序源码里写环境地址、租户、账号或秘密。配置定义、默认值、校验规则和小程序配置导出都由 `ops/configuration.ps1` 维护。

## 常用命令

```powershell
.\config-manager.cmd init
.\config-manager.cmd wizard
.\config-manager.cmd status
.\config-manager.cmd validate
.\config-manager.cmd export-miniapp
```

- `init`：创建或无损补全配置，内部密码和主密钥使用密码学安全随机数生成。
- `wizard`：交互填写真实外部信息；秘密输入不回显，直接回车保留已有值。
- `status`：按分类显示配置状态，秘密只显示末四位。
- `validate`：检查全部基础配置以及所有已启用能力；任何错误都会以非零状态退出。
- `export-miniapp`：从统一配置生成 Git 忽略的小程序部署文件。

`docker-start.cmd` 启动前会自动执行同一套校验，不会用假地址、默认账号、模拟支付或测试租户绕过缺失配置。

## 配置分类

| 分类 | 配置项 | 来源或用途 |
|---|---|---|
| 核心秘密 | `POSTGRES_PASSWORD`、`VALKEY_PASSWORD`、`QR_SIGNING_SECRET`、`DEVICE_CREDENTIAL_MASTER_KEY_BASE64`、`AUTH_JWT_SECRET_BASE64` | `init` 自动生成；不得提交、共享或在日志中输出 |
| OIDC 身份 | `APP_JWT_ISSUER`、`OIDC_ISSUER_URI`、`API_JWT_AUDIENCE`、`ALLOWED_ORIGINS` | 服务端令牌签发、第三方令牌校验和跨域白名单 |
| 管理端登录 | `VITE_OIDC_AUTHORIZATION_ENDPOINT`、`VITE_OIDC_TOKEN_ENDPOINT`、`VITE_OIDC_CLIENT_ID`、`VITE_OIDC_REDIRECT_URI`、`VITE_OIDC_SCOPES` | 管理端 Authorization Code + PKCE；客户端必须是 public client，不保存 client secret |
| 微信身份 | `WECHAT_IDENTITY_ENABLED`、`WECHAT_APP_ID`、`WECHAT_APP_SECRET`、`WECHAT_TENANT_CODE` | 有真实小程序资质后启用 |
| 微信通知 | `WECHAT_NOTIFICATION_ENABLED`、`WECHAT_NOTIFICATION_TEMPLATES_JSON` | 通知类型到微信订阅消息模板的映射；依赖微信身份配置 |
| 微信支付 | `WECHAT_PAYMENT_ENABLED`、`WECHAT_PAYMENT_DIRECTORY`、`WECHAT_PRIMARY_MERCHANT_SERIAL`、`WECHAT_PRIMARY_API_V3_KEY`、`WECHAT_PRIMARY_PUBLIC_KEY_ID` | 密钥目录还必须包含 `merchant-private-key.pem` 和 `wechat-pay-public-key.pem` |
| 设备网关 | `DEVICE_GATEWAY_ENABLED`、`DEVICE_GATEWAY_BIND_ADDRESS`、`DEVICE_TLS_DIRECTORY` | 启用后证书目录必须包含 `tls.crt`、`tls.key`、`ca.crt` |
| 小程序三环境 | `MINIAPP_DEVELOP_*`、`MINIAPP_TRIAL_*`、`MINIAPP_RELEASE_*` | 每套包含 HTTPS API、租户编码和订阅模板 ID 数组 |
| 本机端口 | `ADMIN_WEB_PORT`、`CORE_PORT`、`DEVICE_GATEWAY_PORT`、`DEVICE_MANAGEMENT_PORT`、`POSTGRES_PORT`、`VALKEY_PORT`、`NATS_PORT`、`NATS_MONITOR_PORT` | 校验范围和端口冲突 |
| 性能参数 | `DATABASE_POOL_*`、`ACCESS_TOKEN_MINUTES`、`REFRESH_TOKEN_DAYS`、`RATE_LIMIT_*`、`TRACING_SAMPLE_RATE`、`OUTBOX_PUBLISHER_DELAY_MS`、`APPLICATION_LOG_LEVEL` | Docker 和服务端使用同一份值 |

OIDC 四个最容易混淆的值：

- `OIDC_ISSUER_URI` 是身份平台的签发者地址，服务端用它发现公钥并校验令牌。
- `VITE_OIDC_AUTHORIZATION_ENDPOINT` 是浏览器跳转登录的地址。
- `VITE_OIDC_TOKEN_ENDPOINT` 是管理端用授权码和 PKCE 换令牌的地址。
- `VITE_OIDC_CLIENT_ID` 是在身份平台给管理端创建的 public client 标识，不是用户名，也不是 secret。

## 不放在部署文件里的业务配置

以下内容属于租户业务数据，必须通过受控开通接口或管理后台写入数据库，而不是写入 `.env.docker`：

- 租户、管理员和员工成员关系；
- 场站、设备、端口和设备凭据；
- 费率、营业规则、协议和告警规则；
- 微信商户号、AppID、回调地址、通道启停和 `secret_reference`；
- 订单、充电会话、计量、支付、退款、账务和对账记录。

本机首个微信支付通道的 `secret_reference` 使用 `env:WECHAT_PRIMARY`，实际秘密来自统一部署配置和只读密钥目录。新增多个商户时，生产环境应由秘密管理服务注入各自前缀，不能把私钥写入数据库。

## 生产环境要求

`.env.docker` 适合单机验收和单机部署。规模化生产仍沿用相同变量名和校验规则，但应由云秘密管理、Docker Secret 或编排平台 Secret 注入秘密，并限制配置文件和证书目录的操作系统访问权限。公开域名、OIDC、小程序 API、支付回调和设备入口必须使用可信 HTTPS/TLS；只有本机回环开发地址允许 HTTP。
