local orderId = ARGV[1]
local pendingKey = 'seckill:reservation:pending'
local timeoutKey = 'seckill:reservation:timeout'
local retryKey = 'seckill:reservation:retry'
local pendingCountKey = 'seckill:reservation:pending-count'
local pending = redis.call('hget', pendingKey, orderId)

redis.call('hdel', pendingKey, orderId)
redis.call('zrem', timeoutKey, orderId)
redis.call('hdel', retryKey, orderId)
if not pending then
    return 0
end

local voucherId = string.match(pending, '^([^:]+):')
if voucherId then
    local remaining = redis.call('hincrby', pendingCountKey, voucherId, -1)
    if remaining <= 0 then
        redis.call('hdel', pendingCountKey, voucherId)
    end
end
return 1
