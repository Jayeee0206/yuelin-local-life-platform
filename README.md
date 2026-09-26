# YueLin Local Life

一个面向本地生活场景的前后端示例项目，包含短信验证码式登录、商户查询、达人探店、关注、优惠券秒杀、订单异步处理与可靠性补偿等功能。

> 本仓库用于开发、学习和验收，不代表生产环境已就绪。生产部署前仍需完成容量规划、安全基线、密钥托管、备份恢复、可观测性和真实故障演练。

## 技术栈

- 后端：Java 17、Spring Boot 2.7、MyBatis-Plus
- 数据：MySQL 8、Redis 7
- 消息：RabbitMQ 3（可通过配置关闭）
- 前端：静态 HTML/CSS/JavaScript，以及仓库内随附的 Vue 2、Axios、Element UI 浏览器资源
- 部署：Docker Compose、Nginx 官方容器镜像
- 质量：JUnit、Maven、Node.js 静态检查、Playwright 浏览器验收

## 目录

```text
.
├── src/                         # Spring Boot 业务代码、配置和数据库快照
├── frontend/                    # 静态前端
├── deploy/nginx/default.conf    # 容器反向代理配置
├── scripts/                     # 可复现的质量与验收脚本
├── docs/                        # API、部署、验收和质量文档
├── compose.yaml                 # 完整容器编排
├── Dockerfile                   # 应用镜像构建
└── .github/workflows/ci.yml     # 持续集成
```

## 快速开始

### 前置条件

直接开发至少需要：

- JDK 17
- MySQL 8
- Redis 7
- Maven 3.9 或兼容版本
- RabbitMQ 3（仅在启用消息模式时需要）

容器部署需要 Docker Engine 与 Docker Compose v2。

### 1. 准备配置

应用只从环境变量读取运行时连接信息和权限配置。复制示例文件后填写本机值：

```bash
cp .env.example .env
```

必须自行设置：

- `DB_PASSWORD`
- `MYSQL_ROOT_PASSWORD`（Docker Compose 使用）
- `RABBITMQ_PASSWORD`（启用消息或使用完整 Compose 时）

`.env` 已被 Git 与 Docker 构建上下文排除。不要把真实口令、Token、私钥或个人信息写回仓库。

### 2. 初始化数据库

创建空数据库后导入：

```text
src/main/resources/db/yuelin_local_life.sql
```

SQL 中的用户昵称、号码和商户联系方式均为确定性合成演示数据，不应用作真实联系信息。

### 3. 启动方式

完整容器方式见 [部署指南](docs/deployment.md)。在已配置外部依赖的开发环境中，也可以用标准 Spring Boot/Maven 流程运行后端，并通过 Nginx 或其他静态服务器提供 `frontend/`。

前端请求 `/api/*`，正式反向代理规则位于 `deploy/nginx/default.conf`。不要直接提交只适用于个人电脑的绝对路径或本机代理配置。

## 配置要点

| 变量 | 用途 | 默认/要求 |
|---|---|---|
| `DB_URL` | JDBC 连接串 | 本机开发地址 |
| `DB_USERNAME` | 数据库用户 | `yuelin` |
| `DB_PASSWORD` | 数据库口令 | 必须设置 |
| `REDIS_HOST` / `REDIS_PORT` | Redis 地址 | `127.0.0.1:6379` |
| `REDIS_PASSWORD` | Redis 口令 | 可为空，仅限受信开发环境 |
| `RABBITMQ_HOST` / `RABBITMQ_PORT` | RabbitMQ 地址 | `127.0.0.1:5672` |
| `RABBITMQ_USERNAME` | RabbitMQ 用户 | `yuelin` |
| `RABBITMQ_PASSWORD` | RabbitMQ 口令 | 启用消息时必须设置 |
| `YUELIN_MESSAGING_ENABLED` | 是否启用异步消息链路 | 默认关闭；Compose 中开启 |
| `YUELIN_ADMIN_USER_IDS` | 管理接口用户 ID 白名单 | 空值表示无人被授权 |
| `YUELIN_IMAGE_DIR` | 上传图片目录 | `./data/images` |

所有可配置项及可靠性参数见 [.env.example](.env.example) 和 `src/main/resources/application.yaml`。

## 验证

### 后端单元测试与构建

```bash
mvn -B test
mvn -B -DskipTests package
```


### 仓库与前端静态检查

```bash
npm ci
npm run check:hygiene
npm run check:static
```

`check:hygiene` 检查当前已跟踪树中的常见秘密格式、个人信息形态、本机绝对路径和禁止提交的临时资产。它不替代针对完整 Git 历史的专业秘密扫描。

### 隔离浏览器渲染检查

安装 Playwright Chromium 后：

```bash
npx playwright install chromium
npm run check:render
```

该检查使用确定性网络桩件，不连接后端、数据库或 Redis。

完整栈验收步骤和验证边界见 [验收指南](docs/ACCEPTANCE.md)。

## 可靠秒杀设计

秒杀链路包含 Lua 原子预扣、数据库唯一约束、消息处理幂等、库存补偿、延迟对账和审计记录。设计意图、失败窗口和仍需真实基础设施验证的范围见：

- [可靠秒杀失败矩阵](docs/quality/reliable-seckill-failure-matrix.md)
- [API 契约](docs/api-contracts.md)

这些机制降低重复下单和状态漂移风险，但不能代替生产级压测、断网/重启演练和监控告警。

## 安全与隐私

- 不提交 `.env`、访问 Token、私钥、真实账号口令或个人信息。
- 示例账号和号码必须使用保留域或明确标记的不可路由合成值。
- 公开发布前同时扫描当前树与所有可达 Git 历史；删除当前文件不会自动清除历史对象。
- 如秘密曾进入 Git，先轮换/吊销，再按 [安全策略](SECURITY.md) 协调历史重写。
- 仓库内浏览器依赖版本较旧，风险说明见 [第三方声明](THIRD_PARTY_NOTICE.md)。

## 文档

- [部署指南](docs/deployment.md)
- [验收指南](docs/ACCEPTANCE.md)
- [API 契约](docs/api-contracts.md)
- [性能基准方案](docs/benchmark/README.md)
- [安全策略](SECURITY.md)
- [第三方声明](THIRD_PARTY_NOTICE.md)

## 许可

项目自身代码的许可见 [LICENSE](LICENSE)。第三方组件及演示媒体不因项目许可而自动获得相同授权，详见 [THIRD_PARTY_NOTICE.md](THIRD_PARTY_NOTICE.md)。
