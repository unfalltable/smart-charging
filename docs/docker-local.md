# Docker 空库部署

这套 Compose 用于在单机上以生产鉴权行为验证系统。它启动 PostgreSQL、Valkey、NATS、核心 API 和管理后台，但不写入任何租户、用户、场站、设备、费率、订单或支付记录。

## 必需条件

- Docker Desktop 已启动。
- 可访问的真实 OIDC Issuer，且已创建管理端 PKCE 客户端。
- OIDC 客户端允许回调 `http://127.0.0.1:8088/`。
- OIDC 令牌包含服务端配置的 audience；管理账号包含岗位 scope。

## 配置和启动

首次运行：

```powershell
.\config-manager.cmd init
.\config-manager.cmd wizard
.\config-manager.cmd validate
.\docker-start.cmd
```

`init` 生成 `.env.docker` 和高强度随机内部秘密；`wizard` 收集真实外部配置；`validate` 一次检查必填值、URL、端口、密钥长度、JSON、证书文件和已启用能力。至少需要提供：

- `OIDC_ISSUER_URI`
- `VITE_OIDC_AUTHORIZATION_ENDPOINT`
- `VITE_OIDC_TOKEN_ENDPOINT`
- `VITE_OIDC_CLIENT_ID`
- 必要时调整 `APP_JWT_ISSUER`、`API_JWT_AUDIENCE`、`ALLOWED_ORIGINS`、scope 和回调地址

配置不完整时启动脚本会列出缺失项并停止。运行 `.\config-manager.cmd status` 可以脱敏查看全部配置，不会输出完整密码、AppSecret、APIv3 密钥或主密钥。启动完成后：

如果脚本报告检测到旧演示环境，先确认旧数据无需保留，再执行 `.\docker-stop.cmd -DeleteData`。该命令会删除旧 PostgreSQL、Valkey、NATS 数据卷和旧秘密文件，随后启动会创建全新的空库。

| 服务 | 地址 |
|---|---|
| 管理后台 | `http://127.0.0.1:8088/` |
| 反向代理 API | `http://127.0.0.1:8088/api/v1` |
| 核心健康检查 | `http://127.0.0.1:18080/actuator/health` |

## 开通真实租户

从 OIDC 获取带 `SCOPE_internal` 的服务令牌，仅在当前 PowerShell 会话中设置：

```powershell
$env:INTERNAL_PROVISIONING_TOKEN = Read-Host 'OIDC provisioning token'
.\ops\provision-tenant.ps1 -TenantCode $tenantCode -TenantDisplayName $tenantName -AdminSubject $oidcSubject -AdminDisplayName $adminName
Remove-Item Env:INTERNAL_PROVISIONING_TOKEN
```

接口在一个数据库事务内创建真实租户、平台用户和首个 `TENANT_ADMIN` 成员，并写入审计记录。日常成员管理必须从管理后台执行。

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

该操作会删除本 Compose 项目的 PostgreSQL、Valkey 和 NATS 数据卷，无法恢复。

全部字段及生产环境的秘密管理边界见 [统一配置管理](configuration.md)。
