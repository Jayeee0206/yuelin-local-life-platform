# 可靠秒杀设计

## 一致性边界

秒杀请求先通过 Lua 原子完成 Redis 库存预扣、一人一单校验和预约登记。预约包含订单号、用户、优惠券、创建时间及重试次数。

数据库侧在同一事务中先插入订单、再以 `stock > 0` 条件扣减库存。`tb_voucher_order(user_id, voucher_id)` 唯一索引是并发重复消息的最终防线。

## 消息可靠性

- 生产端启用 RabbitMQ correlated Publisher Confirm 与 mandatory Return；
- 同步发送异常、Broker Nack、不可路由 Return 都触发幂等 Lua 补偿；
- 消费异常最多重试 3 次，之后进入 `yuelin.seckill.order.dlq`；
- 重复消息由业务预检查与数据库唯一索引共同幂等处理；
- 消费事务完成后才清理 Redis 预约。

## 崩溃恢复与对账

定时任务扫描超时预约：

1. 数据库已有订单：清理预约；
2. 数据库无订单且未超过恢复次数：原订单号重新投递；
3. 超过恢复次数：归还 Redis 库存并移除一人一单标记；
4. 原子认领 Lua 防止多实例重复恢复同一预约。

库存审计遵循：

`Redis 可用库存 = 数据库库存 - 待处理预约数`

审计任务只报告差异，不在并发请求期间直接覆盖在线库存。死信队列需由运维或后续管理工具保留、检查和处理。

## Redis 状态

- `seckill:stock:{voucherId}`：可用库存；
- `seckill:order:{voucherId}`：已取得资格的用户；
- `seckill:reservation:pending`：订单预约详情；
- `seckill:reservation:timeout`：预约超时索引；
- `seckill:reservation:retry`：恢复次数；
- `seckill:reservation:pending-count`：按券统计待处理预约。
