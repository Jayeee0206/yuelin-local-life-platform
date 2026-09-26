# 部署指南

本文描述可复现的容器部署流程与生产交付边界。仓库只保留 `deploy/nginx/default.conf` 这一份项目级 Nginx 配置；Nginx 运行时由官方容器镜像提供，不在 Git 中复制操作系统发行包。

## 1. 组件与端口

| 组件 | 容器内端口 | 默认主机端口 | 说明 |
|---|---:|---:|---|
| Nginx | 80 | 8080 | 静态前端与 `/api/` 反向代理 |
| Spring Boot | 8082 | 不直接暴露 | API 服务 |
| MySQL | 3306 | 3306 | 业务数据库 |
| Redis | 6379 | 6379 | 缓存与秒杀预扣 |
| RabbitMQ | 5672 / 15672 | 5672 / 15672 | 消息与管理界面 |

如主机端口冲突，请在私有部署清单中覆盖端口，不要把个人机器配置写回仓库。

## 2. 配置秘密

复制模板并填写随机、唯一的部署值：

```bash
cp .env.example .env
```

完整 Compose 至少要求：

```text
DB_PASSWORD
MYSQL_ROOT_PASSWORD
RABBITMQ_PASSWORD
```

建议同时显式设置 `DB_USERNAME`、`RABBITMQ_USERNAME` 和 `YUELIN_ADMIN_USER_IDS`。默认 Redis 只绑定主机回环地址且未启用认证；生产部署必须在私有覆盖配置中启用 Redis 认证，并把 `REDIS_PASSWORD` 注入应用。生产环境应由秘密管理服务或编排平台注入，避免把 `.env` 作为长期秘密存储。

安全要求：

1. 每个环境使用不同凭据，并限制数据库与消息账号权限。
2. 不在命令行历史、截图、日志、Issue 或 CI 输出中打印值。
3. 不将 `.env` 打包进镜像；`.dockerignore` 已排除本地环境文件。
4. 怀疑泄露时先轮换/吊销，再调查 Git 历史和构建产物。

## 3. 部署前校验

在填好 `.env` 后先只渲染配置，不启动容器：

```bash
docker compose config --quiet
```

再运行代码层检查：

```bash
mvn -B test
mvn -B -DskipTests package
npm ci
npm run check:hygiene
npm run check:static
```

如要运行隔离的前端渲染检查：

```bash
npx playwright install chromium
npm run check:render
```

## 4. 构建与启动

确认校验通过后：

```bash
docker compose build --pull
docker compose up -d
```

查看状态与日志：

```bash
docker compose ps
docker compose logs --tail=200 app nginx
```

不要用“容器已启动”替代业务验收。继续按 [验收指南](ACCEPTANCE.md) 验证健康检查、登录、商户查询、上传、下单、补偿和重启恢复。

## 5. 数据与持久化

- MySQL 首次创建数据卷时会导入 `src/main/resources/db/yuelin_local_life.sql`。
- `mysql-data`、`redis-data` 与 `rabbitmq-data` 是具名卷；删除前必须确认备份。
- 应用上传目录映射到仓库外的运行时数据位置，不应提交用户上传内容。
- 数据库快照中的联系方式只是合成夹具；生产环境不得导入真实用户数据到公开演示环境。

建议至少验证：

1. 数据库逻辑备份与恢复。
2. Redis 丢失后的缓存重建和订单对账。
3. RabbitMQ 消息积压、重投和死信处置。
4. 应用与代理滚动重启。

## 6. Nginx

正式配置位于 `deploy/nginx/default.conf`，负责：

- 提供 `frontend/` 静态文件；
- 将 `/api/` 代理到应用容器；
- 转发必要请求头；
- 将请求体上限与应用上传上限保持一致。

变更上传限制时，应同时检查 Spring Boot 配置、Nginx 配置和前端错误提示。

公网部署还应在外层负载均衡器或 Nginx 增加 TLS、HSTS、访问日志脱敏、安全响应头和可信代理边界。仓库默认配置不是完整的互联网安全基线。

## 7. 健康检查与验收边界

Compose 健康检查只说明进程及依赖在当前探测条件下可响应，不证明：

- 并发容量满足目标；
- 故障补偿在真实中间件中已演练；
- 数据备份可恢复；
- 安全配置适合公网；
- 云端 CI 成功等同生产可用。

性能目标、故障注入方法和验收证据应在目标环境单独记录，不将带主机信息、真实账号或临时日志的报告提交到源代码仓库。

## 8. 停止服务

停止但保留数据：

```bash
docker compose down
```

不要在未备份时使用删除卷参数。
