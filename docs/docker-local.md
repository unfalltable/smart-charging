# Docker 本机一键运行

这套配置只用于本机演示和开发联调。它会启动 PostgreSQL、Valkey、NATS、核心 API、设备网关、管理后台、试点数据初始化任务和一台 12 口模拟充电桩。

## 准备

安装并启动 Docker Desktop，使用 Linux 容器模式。建议给 Docker Desktop 分配至少 4 核 CPU、6 GB 内存和 15 GB 可用磁盘；8 GB 内存更顺畅。项目本身不要求宿主机安装 Java、Maven、Node.js、PostgreSQL 或 Redis。

默认占用以下本机端口：

| 用途 | 地址或端口 |
| --- | --- |
| 管理后台 | `http://127.0.0.1:8088/` |
| API（经管理端反向代理） | `http://127.0.0.1:8088/api/v1` |
| 核心 API（诊断） | `http://127.0.0.1:8080` |
| 设备 TCP 网关 | `127.0.0.1:9000` |
| 网关健康检查 | `http://127.0.0.1:9001` |
| PostgreSQL | `127.0.0.1:5432` |
| Valkey | `127.0.0.1:6379` |
| NATS / 监控 | `127.0.0.1:4222` / `http://127.0.0.1:8222` |

如端口冲突，首次启动后修改根目录自动生成的 `.env.docker`，再重新启动。

## 一键启动与流程演示

在资源管理器中双击 `docker-start.cmd`，或在 PowerShell 中运行：

```powershell
.\docker-start.cmd
```

第一次会下载基础镜像、编译应用并自动生成随机本机密钥，通常需要几分钟。脚本会等到服务健康且试点数据初始化完成，再输出管理后台地址和 1 号充电位的二维码内容。

随后双击 `docker-demo.cmd`，它会自动跑通一次完整本地流程：

```text
创建订单 → 下发启动命令 → 模拟桩确认 → 5 次计量 → 停止并计费 → 模拟微信支付成功
```

刷新 `http://127.0.0.1:8088/`，即可在设备、订单、财务和审计页面查看结果。本地演示模式不要求 OIDC 登录，也不会调用真实微信支付。

## 微信小程序联调

小程序界面必须运行在微信开发者工具或微信客户端中，不能作为 Docker 容器运行。Docker 已承载它依赖的全部服务。

1. 在微信开发者工具中导入 `apps/miniapp`。
2. 本地开发配置默认请求 `http://127.0.0.1:8088/api/v1`，通过管理端 Nginx 转发到核心服务，项目已关闭开发工具的域名校验。
3. 点击“我的 → 登录”，本机 `local` profile 会返回测试用户会话。
4. 使用启动脚本输出的二维码内容制作二维码后扫码，或直接用 `ops/pilot-create-order.http` 调接口。

真机预览时，手机里的 `127.0.0.1` 指向手机自身。需要把 `apps/miniapp/src/config.js` 的 `develop.apiBase` 改为电脑的局域网 IPv4 地址（默认端口 8088），并允许 Windows 防火墙访问对应端口。体验版和正式版的地址仍是无效占位域名，发布前必须替换为已备案且配置 HTTPS 的真实 API 域名。

Compose 只监听 IPv4 回环地址。请使用 `127.0.0.1`，不要使用可能被 Windows 解析到 IPv6 `::1` 或被本机旧服务占用的 `localhost`；若访问 `localhost` 出现与本项目不符的 401，直接改用上面的 IPv4 地址。

## 停止和清空

停止容器但保留数据库：

```powershell
.\docker-stop.cmd
```

删除容器并清空 PostgreSQL、Valkey、NATS 的本地数据卷：

```powershell
.\docker-stop.cmd -DeleteData
```

`.env.docker` 只保存在本机并被 Git 忽略。不要把本机演示模式、其中的密钥或关闭鉴权的 `local` profile 用于公网生产环境。

## 启动回归验证

Windows PowerShell 5.1 下可运行脚本回归测试（不需要 Docker）：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File ops/tests/local-scripts.test.ps1
```

此测试校验二维码签名、订单 `orderId` 字段、模拟支付流程，以及支付未到账时拒绝报告成功；API 使用测试替身。后端 `mvn verify` 中的启动测试会实际启动 HTTP 服务并请求 readiness，网关还会验证 TCP 端口能连接；数据库和消息代理使用测试替身。

GitHub Actions 的 `docker-smoke` 任务负责构建并启动整套 Compose，再连续执行两次充电及模拟支付、检查管理后台及反向代理。只有该任务通过，或本机实际完成 `docker-start.cmd` 和 `docker-demo.cmd`，才代表完整容器流程验证通过。
