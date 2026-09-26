if ARGV[4] == '1' then
    redis.call('zrem', KEYS[1], ARGV[1])
end
return redis.call('geoadd', KEYS[2], ARGV[2], ARGV[3], ARGV[1])
