# Docker 空库部署

这套 Compose 用于在单机上以生产鉴权行为验证系统。它启动 PostgreSQL、Valkey、NATS、核心 API 和管理后台，不会写入任何业务租户、场站、设备、费率、订单或支付记录。

## 启动

电脑只需安装并启动 Docker Desktop，并能访问 Docker Hub 或已配置可用的镜像代理。首次运行：

```powershell
.\config-manager.cmd init
notepad.exe .env
.\config-manager.cmd validate
.\docker-start.cmd
```

默认使用内置数据库账号，不需要 Keycloak、外部 OIDC、Java、Maven 或 Node.js。`init` 会生成 Git 忽略的 `.env`、服务秘密和唯一的平台超级管理员初始凭据。之后直接编辑 `.env`；`validate` 会检查必填值、URL、端口、密钥、JSON、证书目录和已启用能力。

启动完成后的本机地址：

| 服务 | 地址 |
|---|---|
| 管理后台 | `http://127.0.0.1:8088/` |
| 反向代理 API | `http://127.0.0.1:8088/api/v1` |
| 核心健康检查 | `http://127.0.0.1:18080/actuator/health` |

## 首次登录与账号分配

读取初始平台超级管理员凭据：

```powershell
.\config-manager.cmd credentials
```

打开管理后台，使用临时密码登录并立即设置新密码。平台超级管理员不属于任何下游租户：先在“平台与租户”创建真实运营商，再到“账号与权限”创建账号并分配租户管理员、运营、财务、审计或客服岗位。

管理后台故意不开放匿名注册：消费者注册属于微信/支付宝小程序链路，运营后台账号只能由平台超级管理员分配，防止访客或下游账号自行扩大权限。新建或重置账号时，随机临时密码只显示一次，需通过独立安全渠道交付。

## 数据与升级

`.\docker-stop.cmd` 只停止容器并保留数据。只有确认本机数据不再需要时才能执行：

```powershell
.\docker-stop.cmd -DeleteData
```

该操作会删除本 Compose 项目的 PostgreSQL、Valkey 和 NATS 数据卷，无法恢复，但会保留你维护的 `.env`。旧版 `.env.docker` 会在首次运行新脚本时自动重命名为 `.env`，已有账号和秘密不会丢失；旧的 OIDC/身份模式字段会自动从 `.env` 清理。

## 真实设备、微信和支付

设备网关默认关闭。准备厂家协议适配器及 `tls.crt`、`tls.key`、`ca.crt` 后，在 `.env` 中启用。取得真实微信资质后，在同一文件中配置小程序 AppID/AppSecret、支付 APIv3 密钥与证书、订阅消息模板和正式 HTTPS 地址。系统没有模拟支付成功接口，交易状态只能由验签回调或支付平台主动查询推进。

完整字段和生产秘密管理边界见 [统一配置管理](configuration.md)。单机 Compose 不是公网高可用方案，正式上线门禁见 [生产商用门禁](production-readiness.md)。
