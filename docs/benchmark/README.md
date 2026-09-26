> `scripts/acceptance-infra.py` 提供隔离可重复负载（48 个预置会话、96 请求、16 并发、库存20）与故障验证。它不是 JMeter 运行，也不是登录吞吐或生产容量证明。仅在完整验收入口、真实浏览器成功后运行；结果为 `ci-artifacts/infrastructure-acceptance.json`。未运行或失败时不得填写通过数字。下文 JMeter 专项保留为可选扩展。

# JMeter 秒杀压测

> 仓库只提供可复现脚本，不预填未经实测的吞吐量或延迟数据。

## 前置条件

1. 使用 `docker compose up -d --build` 启动环境；
2. 准备有效秒杀券，并确保 `seckill:stock:{voucherId}` 已初始化；
3. 准备 Redis 登录 Token。可以先运行 `scripts/docker-smoke-test.sh` 创建券 999 和测试 Token；
4. 对“同一用户并发只生成一单”场景，所有线程使用同一 Token；对吞吐场景，应使用 CSV Data Set 提供不同用户 Token。

## 命令行执行

```bash
jmeter -n \
  -t docs/benchmark/seckill-concurrency.jmx \
  -Jscheme=http \
  -Jhost=localhost \
  -Jport=8080 \
  -Jtoken=smoke-token \
  -JvoucherId=999 \
  -Jthreads=100 \
  -JrampUp=10 \
  -Jduration=60 \
  -l docs/benchmark/results.jtl \
  -e -o docs/benchmark/html-report
```

`results.jtl` 与 `html-report` 属于生成结果，不应在未核验前作为项目性能结论。压测后还应核对：

- 同用户同券数据库订单数是否为 1；
- 数据库库存是否非负；
- Redis 库存是否满足 `数据库库存 - 待处理预约数`；
- 主队列、重试和死信队列是否符合预期。
