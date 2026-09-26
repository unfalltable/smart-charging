# Smart Charging Platform V2

面向充电运营商、场站和终端用户的多租户两轮车充电平台。V2 是独立的新实现，不依赖旧系统代码。

## 已实现能力

- Java 21、Spring Boot 4.1、Spring Modulith 的模块化核心服务。
- PostgreSQL 行级安全策略与应用层租户授权双重隔离。
- 平台运营方、一级分包商/区域运营商、二级合作商/场地方、直营网点、场站、设备、端口和终端用户的真实渠道组织模型。
- 费率、订单、充电会话、计量、命令、支付、退款、双式记账、按组织归集的分成与平台服务费、对账结算、钱包、发票、告警工单、协议和审计模型。
- 订单幂等、充电口并发锁、outbox/inbox、支付回调验签、主动查单和异常恢复。
- Netty 设备网关，支持双向 TLS、HMAC、时间窗和 Valkey 分布式 nonce 防重放。
- 微信小程序 code2Session 登录、访问令牌、刷新令牌轮换与复用检测。
- 管理端 OIDC Authorization Code + PKCE、可选自托管 Keycloak、服务端权威租户会话、平台总管理员与下游租户角色分层、分布式限流和请求追踪。
- 微信支付 APIv3 官方 Java SDK、退款、订阅消息发送和外部秘密引用。
- 设备密钥在线生成与轮换，AES-256-GCM 加密存入 Valkey。

## 数据与安全原则

仓库运行包不包含演示租户、固定用户、场站、设备、订单、支付记录、模拟支付接口或模拟充电桩。数据库首次启动只执行表结构迁移，业务表保持为空。

自动化测试仍使用隔离测试夹具；它们只存在于测试源码，不会进入生产 JAR、镜像或数据库。生产配置缺失时系统会拒绝启动，不会用默认身份、无效域名或固定密钥代替。

## Docker 启动

所有部署参数现在由一套配置管理器维护。首次执行：

```powershell
.\config-manager.cmd init
.\config-manager.cmd validate
.\docker-start.cmd
```

配置管理器把部署参数写入 Git 忽略的 `.env.docker`，自动生成强随机内部秘密和本机自托管 Keycloak 配置，并在启动前统一校验。默认不需要另购或手工填写 OIDC 服务；需要接入企业已有身份平台时，再运行 `.\config-manager.cmd wizard` 切换为外部 OIDC。微信、支付证书、设备 TLS 和小程序正式环境仍必须使用真实资料。运行 `.\config-manager.cmd status` 可脱敏查看配置状态。系统不会自动创建任何业务记录。

如果电脑以前运行过带固定试用数据的旧版本，启动脚本会拒绝沿用旧数据卷。确认旧数据不需要保留后执行 `.\docker-stop.cmd -DeleteData`，再重新启动，即可得到空库和全新的秘密。

启动完成后，先创建自己的真实租户，再使用配置管理器明确读取一次初始登录信息：

```powershell
.\ops\provision-tenant.ps1 -TenantCode $tenantCode -TenantDisplayName $tenantName
.\config-manager.cmd credentials
```

自托管模式会用专用、最小权限的服务账号获取开通令牌，不需要把令牌写入配置文件。初始平台密码是临时密码，首次登录必须修改。若切换到外部 OIDC，开通脚本仍要求显式提供真实的 `INTERNAL_PROVISIONING_TOKEN`、管理员 subject 和显示名称。

真实设备网关默认不启动。准备好设备协议适配器和 mTLS 证书后，通过配置向导启用；启动脚本会检查三份真实证书后才加载网关容器。

完整配置清单见 [统一配置管理](docs/configuration.md)，启动流程见 [Docker 部署说明](docs/docker-local.md)。停止服务运行 `.\docker-stop.cmd`；只有明确需要不可恢复地清除本机数据库时才运行 `.\docker-stop.cmd -DeleteData`。

## 小程序

微信小程序必须在微信开发者工具或微信客户端运行。配置管理器根据开发版、体验版和正式版设置生成不入库的 `deployment.config.js`；不再修改源码，也不会附带假地址或占位租户。所选环境缺少真实 HTTPS API 地址或租户编码时，小程序会直接拒绝启动。

## 验证

```powershell
$env:JAVA_HOME=(Resolve-Path '.tools\jdk-21.0.12.1+1').Path
.\.tools\apache-maven-3.9.16\bin\mvn.cmd -B -ntp clean verify
npm run build:web
npm run check:weapp
.\ops\tests\local-scripts.test.ps1
```

资质、生产凭据、真实设备和基础设施验收清单见
[外部输入](docs/external-inputs.md) 与 [生产商用门禁](docs/production-readiness.md)。在真实支付、真实桩机、备份恢复、安全和容量验收完成前，不能宣称已经可以公开收费运营。

## 模块

- `platform-contracts`：设备命令与事件契约。
- `platform-core`：租户、资产、充电、计费支付、账务和运营核心。
- `device-gateway`：设备长连接、安全认证和消息接入。
- `apps/admin-web`：运营管理 Web。
- `apps/miniapp`：原生微信小程序。
- `ops`：镜像、空库部署、受控租户开通和运维脚本。

## 许可

本仓库的一方业务代码为专有软件，未经权利人书面许可不得复制、分发或商用。第三方依赖继续遵守各自许可证。
