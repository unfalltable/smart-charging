# Docker 空库部署

这套 Compose 用于在单机上以生产鉴权行为验证系统。它启动 PostgreSQL、Valkey、NATS、Keycloak、核心 API 和管理后台，但不写入任何业务租户、场站、设备、费率、订单或支付记录。

## 必需条件

- Docker Desktop 已启动。
- Docker Desktop 能访问 Docker Hub 和 `quay.io`，或已经配置可用的镜像加速/代理。

默认使用 Compose 内自托管的 Keycloak，不要求事先准备外部 OIDC。若切换到企业已有身份平台，该平台必须支持 Authorization Code + PKCE、配置正确的 audience、岗位角色和回调地址。

## 配置和启动

首次运行：

```powershell
.\config-manager.cmd init
.\config-manager.cmd validate
.\docker-start.cmd
```

启动器会复用已经构建成功的本地 Keycloak 优化镜像，日常重启或更新业务代码时不会再次访问 `quay.io`。只有本机从未成功构建过该镜像时才需要连接官方 Keycloak 镜像仓库。

`init` 生成 `.env.docker`、高强度随机秘密和 Git 忽略的 Keycloak Realm；`validate` 一次检查必填值、URL、端口、密钥长度、JSON、证书文件和已启用能力。原来缺少的以下四项在默认自托管模式中会自动生成，无须手填：

- `OIDC_ISSUER_URI`
- `VITE_OIDC_AUTHORIZATION_ENDPOINT`
- `VITE_OIDC_TOKEN_ENDPOINT`
- `VITE_OIDC_CLIENT_ID`

要修改首个管理员名称、接入微信/支付/设备或切换外部 OIDC，再运行 `.\config-manager.cmd wizard`。运行 `.\config-manager.cmd status` 可以脱敏查看全部配置；只有显式运行 `.\config-manager.cmd credentials` 才会显示本机初始登录秘密。

配置不完整时启动脚本会列出缺失项并停止。启动完成后：

如果脚本报告检测到旧演示环境，先确认旧数据无需保留，再执行 `.\docker-stop.cmd -DeleteData`。该命令会删除旧 PostgreSQL、Valkey、NATS 数据卷和旧秘密文件，随后启动会创建全新的空库。

| 服务 | 地址 |
|---|---|
| 管理后台 | `http://127.0.0.1:8088/` |
| 反向代理 API | `http://127.0.0.1:8088/api/v1` |
| 核心健康检查 | `http://127.0.0.1:18080/actuator/health` |
| 身份服务 | `http://127.0.0.1:19090/` |

## 开通真实租户

默认自托管模式已配置一个只能调用内部开通接口的服务账号。填写你自己的真实租户编码和名称：

```powershell
$tenantCode = Read-Host 'Tenant code (lowercase letters, numbers and hyphens)'
$tenantName = Read-Host 'Tenant display name'
.\ops\provision-tenant.ps1 -TenantCode $tenantCode -TenantDisplayName $tenantName
.\config-manager.cmd credentials
```

接口会从 Keycloak 查询该登录用户的真实 `subject`，再在一个数据库事务内创建真实租户、平台用户和首个 `TENANT_ADMIN` 成员，并写入审计记录。相同租户编码可以安全重复执行；即使旧数据库中的租户 ID 与后来生成的 `INITIAL_TENANT_ID` 不一致，也会复用数据库真实租户并补齐管理员关系，不会创建重复租户或返回冲突。登录后管理端通过服务端会话接口读取权威租户列表，而不是直接使用可能过期的令牌租户声明。然后用显示的临时密码登录并立即修改密码。日常成员管理必须从管理后台执行。

外部 OIDC 模式不保存外部客户端秘密；开通时仍需在当前 PowerShell 会话提供带 `SCOPE_internal` 的真实服务令牌，并传入 `-AdminSubject` 和 `-AdminDisplayName`。

## 接入真实设备

1. 根据厂家协议实现并验收协议适配器。
2. 在管理后台创建真实场站和设备，填写设备实际端口数及额定功率。
3. 轮换设备凭据，并通过安全通道写入实体设备。
4. 准备 `tls.crt`、`tls.key`、`ca.crt`，通过配置向导填写目录并启用设备网关。
5. 运行 `.\config-manager.cmd validate` 和 `.\docker-start.cmd`。

网关不接受明文生产连接，也没有内置设备编码或固定密钥。

## 微信小程序和支付

取得真实微信资质后：

1. 在配置向导中启用微信身份，填写 AppID、AppSecret 和真实租户编码。
2. 配置微信支付证书目录、APIv3 密钥等秘密，再在管理后台创建微信商户通道并引用 `env:WECHAT_PRIMARY`。
3. 在配置向导中填写小程序对应版本的真实 HTTPS API 地址和租户编码；工具自动生成本机部署配置。
4. 使用实体设备和微信支付环境执行下单、启动、计量、停止、结算、支付、退款和对账验收。

系统没有“支付成功”模拟接口，支付状态只能由验签回调或支付平台主动查询推进。

## 数据清理

`.\docker-stop.cmd` 只停止容器并保留数据。只有确认不需要数据时才能执行：

```powershell
.\docker-stop.cmd -DeleteData
```

该操作会删除本 Compose 项目的 PostgreSQL、Valkey 和 NATS 数据卷以及本机秘密文件，无法恢复。

全部字段及生产环境的秘密管理边界见 [统一配置管理](configuration.md)。
