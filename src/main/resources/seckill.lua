local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]
local reservedAt = ARGV[4]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local pendingKey = 'seckill:reservation:pending'
local timeoutKey = 'seckill:reservation:timeout'
local retryKey = 'seckill:reservation:retry'
local pendingCountKey = 'seckill:reservation:pending-count'

local stock = tonumber(redis.call('get', stockKey) or '-1')
if stock <= 0 then
    return 1
end
if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end

redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)
redis.call('hset', pendingKey, orderId, voucherId .. ':' .. userId)
redis.call('zadd', timeoutKey, reservedAt, orderId)
redis.call('hset', retryKey, orderId, 0)
redis.call('hincrby', pendingCountKey, voucherId, 1)
return 0
