local key = KEYS[1]
local now = tonumber(ARGV[1])
local requestId = ARGV[2]
local oneMinute = tonumber(ARGV[3])
local fiveMinutes = tonumber(ARGV[4])
local twentyMinutes = tonumber(ARGV[5])
local maxFiveMinutes = tonumber(ARGV[6])
local maxTwentyMinutes = tonumber(ARGV[7])

redis.call('zremrangebyscore', key, 0, now - twentyMinutes)
if redis.call('zcount', key, now - oneMinute, now) >= 1 then
    return 1
end
if redis.call('zcount', key, now - fiveMinutes, now) >= maxFiveMinutes then
    return 2
end
if redis.call('zcount', key, now - twentyMinutes, now) >= maxTwentyMinutes then
    return 3
end

redis.call('zadd', key, now, requestId)
redis.call('expire', key, math.ceil(twentyMinutes / 1000))
return 0
