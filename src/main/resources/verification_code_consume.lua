local cached = redis.call('get', KEYS[1])
if not cached then
    redis.call('del', KEYS[2])
    return -1
end
if cached ~= ARGV[1] then
    local attempts = redis.call('incr', KEYS[2])
    local codeTtl = redis.call('pttl', KEYS[1])
    if codeTtl > 0 then
        redis.call('pexpire', KEYS[2], codeTtl)
    end
    if attempts >= tonumber(ARGV[2]) then
        redis.call('del', KEYS[1], KEYS[2])
        return -2
    end
    return 0
end
redis.call('del', KEYS[1], KEYS[2])
return 1
