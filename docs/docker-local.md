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

完整字段见 [统一配置管理](configuration.md)。以下生产入口使用相同 Compose 和相同 `.env`，不会产生第二套业务系统；单机部署不提供机器故障高可用。

## 单机生产发布

服务器需要 Docker Engine、Compose v2、Git；运行运维脚本还需要 PowerShell 7（Windows 可使用系统 PowerShell 5.1）。所有业务服务运行在容器内。初次安装后运行配置初始化，编辑同一个 `.env`：

```env
PUBLIC_HOST='charging.your-domain.cn'
APP_JWT_ISSUER='https://charging.your-domain.cn'
ALLOWED_ORIGINS='https://charging.your-domain.cn'
HTTP_PORT='80'
HTTPS_PORT='443'
BACKUP_DIRECTORY='runtime-secrets/backups'
```

这里的域名必须替换成你控制的真实域名，DNS A/AAAA 指向对应机器。安全组开放公网 80/443；如果没有配置 IPv6，不要留指向其他机器的 AAAA。需要接入真实桩时再开放设备端口并配置 mTLS，其他端口保持内网/回环访问。在 Linux 上，脚本自动把生成的 `.env`/商户环境文件设为 600、私密配置及备份目录设为 700。

后端和设备网关使用 UID 10001，而不是 root。已有支付/设备私钥须通过受控 ACL 或目录组权限授权此 UID 读取及遍历；例如在确认实际目录后使用 `setfacl -Rm u:10001:rX /实际证书目录`。不要把私钥改为全员可读。启动脚本会用真实镜像用户检查可读性，但不会自动更改用户资料的 owner 或扩大证书权限。

Windows 执行 `docker-start.cmd`。Linux 执行：

```powershell
pwsh -NoProfile -File ./ops/manage-config.ps1 validate
pwsh -NoProfile -File ./ops/start-local.ps1
```

脚本会启用生产 profile、构建对应 Git 提交的镜像、执行无损数据库角色初始化及 Flyway 迁移、等待后端/前端健康，并检查真实域名的 HTTPS 入口。DNS、80/443 或证书申请有问题时会显示 Caddy 日志，不会把仅本机就绪冒充公网发布完成。之后后台地址为 `https://你的域名/`，小程序 API 为 `https://你的域名/api/v1`。

内部数据库账号已经区分初始化、迁移和业务运行；运行账号不能绕过 PostgreSQL RLS。NATS 必须携带随机 `NATS_TOKEN`，Valkey 必须携带随机密码；二者不向公网发布。Java 容器以普通 UID、只读根文件系统和受限临时目录运行；容器日志会按大小轮换。

更新前先做以下备份，再 `git pull --ff-only origin main`、启动。Flyway 会自动校验历史迁移。数据库已经升级后，不能直接把旧镜像重新启动当作回滚；优先向前修复，必须回退时在停机窗口恢复整个一致性快照。

## 一致性备份和恢复

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\ops\backup.ps1
```

Linux 使用 `pwsh -NoProfile -File ./ops/backup.ps1`。备份会短暂停止此部署的容器，等所有写入进程退出，再共同归档 PostgreSQL 数据卷、Valkey 持久数据、NATS JetStream、HTTPS 证书及 `.env`/支付与设备密钥，并保存 SHA-256 清单；完成后恢复原来运行的服务。这样恢复后数据库 outbox/inbox 和消息系统来自同一个停机时点。它是维护窗口备份，会造成短暂停机；生产期应定时执行并把副本加密存到另一台机器或对象存储。备份包含用户数据和密钥，绝不能提交到仓库。

先在隔离机器演练恢复，确认订单、账务、管理员和设备状态，再允许上线。恢复只接受本项目固定数据卷名、完整清单与正确校验和，并需要明确提供 `-ConfirmRestore`：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\ops\restore.ps1 -BackupPath 'D:\private-backups\snapshot-实际编号' -ConfirmRestore
.\docker-start.cmd
```

恢复会替换此部署的数据卷与 `.env`，原 `.env` 保存在 Git 忽略的 `.env.pre-restore-随机编号`，源快照保留；恢复完成时容器保持停止，运维检查后再启动。备份和恢复通过只读挂载读取密钥，并保留 Linux UID/GID 与 ACL。辅助镜像只在维护时运行；缺失时会在停止或替换数据前构建，离线机器应提前准备 `smart-charging-local-snapshot-tool:latest`。备份目录及密钥路径需匹配新机器。备份保留期限、异地副本和恢复时间目标由你按业务量制定。
