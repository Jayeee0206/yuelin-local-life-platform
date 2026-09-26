local voucherId = ARGV[1]
local userId = ARGV[2]
local orderId = ARGV[3]

local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId
local pendingKey = 'seckill:reservation:pending'
local timeoutKey = 'seckill:reservation:timeout'
local retryKey = 'seckill:reservation:retry'
local pendingCountKey = 'seckill:reservation:pending-count'
local expected = voucherId .. ':' .. userId
local pending = redis.call('hget', pendingKey, orderId)

if not pending then
    return 0
end
if pending ~= expected then
    return -1
end

redis.call('hdel', pendingKey, orderId)
redis.call('zrem', timeoutKey, orderId)
redis.call('hdel', retryKey, orderId)
local remaining = redis.call('hincrby', pendingCountKey, voucherId, -1)
if remaining <= 0 then
    redis.call('hdel', pendingCountKey, voucherId)
end
-- This reservation consumed one unit, but the database already owns the user's order.
-- Restore the unit while retaining the user marker so repeated requests stay blocked.
if redis.call('exists', stockKey) == 0 then
    redis.call('sadd', orderKey, userId)
    return -2
end
redis.call('incrby', stockKey, 1)
redis.call('sadd', orderKey, userId)
return 1
