redis.call('del', KEYS[2])
for index = 1, #ARGV, 3 do
    redis.call('geoadd', KEYS[2], ARGV[index + 1], ARGV[index + 2], ARGV[index])
end
redis.call('del', KEYS[1])
if #ARGV > 0 then
    redis.call('rename', KEYS[2], KEYS[1])
end
return #ARGV / 3
