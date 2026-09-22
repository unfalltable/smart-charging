# Smart Charging Platform V2

面向运营商、场站与终端用户的多租户两轮车充电平台。V2 是独立的新实现，不依赖旧系统代码。

## 当前可验证能力

- Java 21 + Spring Boot 4.1 + Spring Modulith 的模块化核心服务。
- PostgreSQL 行级安全策略与应用层租户授权双重隔离。
- 订单、充电会话、计量、设备命令、支付、退款、双式记账、审计、outbox/inbox 数据模型。
- 订单创建幂等、充电口并发锁定、启动命令与 outbox 同事务提交。
- Netty 长连接设备网关，支持双向 TLS、HMAC、时间窗与分布式 nonce 防重放。
- NATS 设备事件通道；Valkey 用于跨实例防重放。
- 微信小程序 code2Session 登录、短期访问令牌、轮换刷新令牌与复用检测；管理端支持 OIDC Authorization Code + PKCE。
- 租户角色、管理端 MFA/OIDC 对接边界、分布式限流、安全响应头、请求追踪和带来源 IP 的审计日志。
- 受 `SCOPE_internal` 保护的租户与首个管理员原子化开通接口，日常权限变更由租户管理员审计管理。
- 场站/设备/端口、费率、订单、支付退款、对账结算、钱包、发票、告警工单、协议签署与客服工单管理界面和 API。
- 微信支付 APIv3 官方 Java SDK：JSAPI 下单参数、回调验签解密、退款、主动查单与异常恢复；商户密钥只通过外部秘密引用加载。
- 微信订阅消息授权和生产发送器，支持共享 access token 缓存、模板字段映射和失败重试。
- 设备密钥在线生成与轮换，AES-256-GCM 加密存入 Valkey，网关跨实例读取并短时缓存。
- 一台 12 口桩的本地试点数据脚本。

## 本机验证

项目要求 Java 21 和 Maven 3.9.9 以上。仓库中的 `.tools` 仅是本机忽略目录，不会提交。

```powershell
$env:JAVA_HOME=(Resolve-Path '.tools\jdk-21.0.12.1+1').Path
.\.tools\apache-maven-3.9.16\bin\mvn.cmd -B -ntp clean verify
```

安装 Docker 后，可复制 `.env.example` 为 `.env`，替换所有密码，再启动本地基础设施和应用：

```powershell
docker compose --env-file .env -f ops/compose.local.yaml up -d --build
.\ops\bootstrap-pilot.ps1
```

生成 1 号充电位的本地签名二维码内容，并启动模拟桩：

```powershell
.\ops\generate-pilot-qr.ps1
$env:PILOT_DEVICE_SECRET='与 .env 相同的设备密钥'
npm run simulate:device
```

把生成内容制作成测试二维码，或用 [ops/pilot-create-order.http](ops/pilot-create-order.http) 直接创建订单。
模拟桩会自动确认启动、上报 5 次计量数据并结束会话，用于验证订单闭环。

本地 profile 只用于开发联调，会关闭 API 身份认证和设备 mTLS。生产环境不得启用 `local` profile。

需要营业资质、商户号、真实域名、证书和桩厂协议的工作没有伪造配置；所需外部材料见
[docs/external-inputs.md](docs/external-inputs.md)。在这些材料到位并完成真机/真实商户验收前，代码通过不代表可以公开收费运营。

## 模块

- `platform-contracts`：设备命令与事件的稳定契约。
- `platform-core`：租户、资产、充电、计费支付、账务与运营核心。
- `device-gateway`：设备长连接、安全认证及消息接入。
- `apps/admin-web`：运营管理 Web。
- `apps/miniapp`：零运行依赖的原生微信小程序。
- `simulator`：可执行的 12 口桩协议模拟器。
- `ops`：本地编排、镜像及试点初始化。

架构边界、扩容原则及商用门禁见 [docs/architecture.md](docs/architecture.md) 和
[docs/production-readiness.md](docs/production-readiness.md)。

## 许可

本仓库的一方业务代码为专有软件，未经权利人书面许可不得复制、分发或商用。第三方依赖继续遵守各自许可证。
