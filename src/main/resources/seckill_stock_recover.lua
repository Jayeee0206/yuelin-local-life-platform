if redis.call('exists', KEYS[1]) == 1 then
    return 0
end
local pending = tonumber(redis.call('hget', KEYS[2], ARGV[1]) or '0')
if pending ~= 0 then
    return -1
end
return redis.call('setnx', KEYS[1], ARGV[2])
