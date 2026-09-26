local ttlSeconds = tonumber(ARGV[2])
if not ttlSeconds or ttlSeconds <= 0 then
    return 0
end
redis.call('set', KEYS[1], ARGV[1], 'EX', ttlSeconds)
redis.call('del', KEYS[2])
return 1
