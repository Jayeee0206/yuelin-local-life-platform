if redis.call('exists', KEYS[1]) == 1 then
    return 0
end
for index = 1, #ARGV do
    redis.call('rpush', KEYS[1], ARGV[index])
end
return 1
