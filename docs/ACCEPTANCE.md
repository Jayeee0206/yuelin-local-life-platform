# 验收指南

本指南定义项目的可重复验证层级。任何结论都应注明执行环境、提交号、命令和失败项；不得把静态检查、单元测试或 CI 绿灯表述为生产验证。

## 1. 验收层级

| 层级 | 目标 | 是否启动服务 |
|---|---|---|
| A. 仓库卫生 | 检查秘密形态、个人信息形态、本机路径和无关资产 | 否 |
| B. 静态与单元 | 编译、单元测试、前端静态规则 | 否 |
| C. 隔离渲染 | 用网络桩件加载关键页面并检查控制台 | 仅浏览器 |
| D. 真实依赖集成 | 验证 MySQL、Redis、RabbitMQ 交互 | 是 |
| E. 完整栈浏览器 | 通过 Nginx 和真实 API 验证用户路径 | 是 |
| F. 生产就绪评估 | 压测、故障注入、恢复、安全和运维演练 | 是，且需专用环境 |

低层级通过不能替代高层级。

## 2. A 级：仓库卫生

```bash
npm ci
npm run check:hygiene
```

检查范围是当前已跟踪工作树，包括常见 Token/私钥格式、非保留邮箱、手机号和身份证号形态、个人主目录绝对路径，以及禁止提交的代理交接文件、运行时发行包和生成报告。

该脚本不扫描提交历史、Git LFS、发布附件、Actions 日志或外部镜像。公开发布前还需使用独立工具扫描所有可达 Git 对象；发现真实秘密时必须先轮换，再评估是否重写历史。

## 3. B 级：静态、单元和构建

```bash
mvn -B test
mvn -B -DskipTests package
npm run check:static
```

关注：

- Java 单元测试全部通过；
- 构建产物可生成；
- 前端关键脚本可解析且无禁止模式；
- API 契约、上传限制和 Nginx 配置保持一致；
- 环境文件与依赖目录被正确排除。

这些步骤不连接真实数据库、中间件或浏览器。

## 4. C 级：隔离前端渲染

```bash
npx playwright install chromium
npm run check:render
```

脚本以 Playwright Chromium 加载关键页面，以确定性桩件替换网络请求，并检查页面断言、脚本异常和控制台错误。它适合发现静态资源、模板和基础交互回归，但不能验证真实 API、鉴权、跨服务网络或数据库状态。

## 5. D 级：真实依赖集成

在隔离 CI 或测试环境中准备 MySQL 8、Redis 7 和 RabbitMQ 3，再执行集成测试。至少验证：

- 首次建库结构和唯一约束；
- Redis Lua 脚本、缓存重建和分布式锁；
- RabbitMQ 发布确认、消费重试和消息幂等；
- 秒杀预扣、订单落库、补偿与对账；
- 服务重启后的恢复行为。

测试凭据必须由 CI 临时生成或秘密存储注入，不得硬编码到工作流或报告。

## 6. E 级：完整栈浏览器验收

先按 [部署指南](deployment.md) 启动完整栈，确认健康检查后执行：

```bash
npm run check:e2e
```

该脚本面向真实 Nginx 和后端，不应在资源受限的个人电脑上误运行。建议仅在可丢弃的 CI 环境执行。

核心路径：

1. 获取验证码并登录演示账号；
2. 查询商户类型、列表和详情；
3. 上传合法图片，并拒绝越限或非法文件；
4. 发布、点赞、关注和查询动态；
5. 查询优惠券并完成一次下单；
6. 验证重复请求、重复下单和越权操作被拒绝；
7. 检查退出登录和令牌失效。

## 7. F 级：生产就绪评估

必须在专用环境单独执行并保存到受控证据系统，而非源代码仓库：

- 目标吞吐和延迟下的容量测试；
- MySQL、Redis、RabbitMQ、应用和网络故障注入；
- 备份恢复与灾难恢复演练；
- TLS、依赖漏洞、镜像和权限基线检查；
- 日志脱敏、指标、追踪与告警闭环；
- 数据保留、删除和访问审计。

秒杀失败窗口见 [可靠秒杀失败矩阵](quality/reliable-seckill-failure-matrix.md)。

## 8. CI 解释

GitHub Actions 工作流包含代码级测试、真实 MySQL/Redis/RabbitMQ 集成、隔离 Chromium 检查和容器编排验收。一次工作流成功只证明对应提交在该次临时环境及既定断言下通过，不等同于：

- 默认分支已经发布；
- 生产配置安全；
- 所有基础设施故障均已覆盖；
- 性能和可用性目标已经达成。

## 9. 证据记录模板

在外部工单或发布系统记录：

```text
commit: <full commit id>
environment: <isolated CI / staging / production-like>
checks: <commands or workflow run>
passed: <verified scope>
failed: <failures>
not-run: <explicit omissions>
artifacts: <restricted artifact links>
reviewer: <team or role, not personal contact data>
```

不得在公开证据中包含真实手机号、邮箱、Token、Cookie、数据库连接串、主机绝对路径或未脱敏日志。
