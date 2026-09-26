local ttlSeconds = tonumber(ARGV[4])
if not ttlSeconds or ttlSeconds <= 0 then
    return 0
end
redis.call('del', KEYS[1])
redis.call('hset', KEYS[1],
    'id', ARGV[1],
    'nickName', ARGV[2],
    'icon', ARGV[3])
redis.call('expire', KEYS[1], ttlSeconds)
return 1
