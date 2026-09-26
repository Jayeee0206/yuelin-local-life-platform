local orderId = ARGV[1]
local cutoff = tonumber(ARGV[2])
local nextCheckAt = tonumber(ARGV[3])
local pendingKey = 'seckill:reservation:pending'
local timeoutKey = 'seckill:reservation:timeout'
local retryKey = 'seckill:reservation:retry'

if redis.call('hexists', pendingKey, orderId) == 0 then
    redis.call('zrem', timeoutKey, orderId)
    redis.call('hdel', retryKey, orderId)
    return -1
end

local score = tonumber(redis.call('zscore', timeoutKey, orderId) or '0')
if score > cutoff then
    return 0
end

local attempt = redis.call('hincrby', retryKey, orderId, 1)
redis.call('zadd', timeoutKey, nextCheckAt, orderId)
return attempt
