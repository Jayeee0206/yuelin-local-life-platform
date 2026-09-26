package com.yuelin.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.yuelin.utils.RedisConstants.CACHE_NULL_TTL;
import static com.yuelin.utils.RedisConstants.LOCK_SHOP_KEY;

@Slf4j
@Component
public class CacheClient {
    //来自鄢坤的更改
    //更改2
    //更改3
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    private static final int MUTEX_RETRY_LIMIT = 40;
    private static final long MUTEX_RETRY_INTERVAL_MS = 50L;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

//    方法1：将任意Java对象序列化为JSON，并存储到String类型的Key中，并可以设置TTL过期时间
    public void set(String key, Object value, Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }
//    方法2：将任意Java对象序列化为JSON，并存储在String类型的Key中，并可以设置逻辑过期时间，用于处理缓存击穿问题
    public void setWithLogicExpire(String key, Object value, Long time, TimeUnit timeUnit) {
        RedisData<Object> redisData = new RedisData<>();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
//    3.根据指定的Key查询缓存，并反序列化为指定类型，利用缓存空值的方式解决缓存穿透问题
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        //先从Redis中查，这里的常量值是固定的前缀 + 店铺id
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //如果不为空（查询到了），则转为R类型直接返回
        if (StrUtil.isNotBlank(json)) {
            return JSONUtil.toBean(json, type);
        }
        if (json != null) {
            return null;
        }

        //否则去数据库中查，查询逻辑用我们参数中注入的函数
        R r = dbFallback.apply(id);

        //查不到，则将空值写入Redis
        if (r == null) {
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
            return null;
        }
        long ttl = addTtlJitter(time);
        this.set(key, r, ttl, timeUnit);
        //最终把查询到的商户信息返回给前端
        return r;
    }

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        //1. 从redis中查询商铺缓存
        String key = keyPrefix + id;
        String json = stringRedisTemplate.opsForValue().get(key);
        //2. 如果未命中，则返回空
        if (StrUtil.isBlank(json)) {
            return null;
        }
        //3. 命中，将json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime = redisData.getExpireTime();
        //4. 判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5. 未过期，直接返回商铺信息
            return r;
        }
        //6. 过期，尝试获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        String lockToken = tryLock(lockKey);
        //7. 获取到了锁
        if (lockToken != null) {
            //8. 开启独立线程
            try {
                CACHE_REBUILD_EXECUTOR.submit(() -> {
                    try {
                        R tmp = dbFallback.apply(id);
                        if (tmp == null) {
                            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                        } else {
                            this.setWithLogicExpire(key, tmp, time, timeUnit);
                        }
                    } catch (Exception rebuildError) {
                        log.error("Failed to rebuild cache for key {}", key, rebuildError);
                    } finally {
                        unlock(lockKey, lockToken);
                    }
                });
            } catch (RuntimeException submitError) {
                unlock(lockKey, lockToken);
                throw submitError;
            }
            //9. 直接返回商铺信息
            return r;
        }
        //10. 未获取到锁，直接返回商铺信息
        return r;
    }

    public <R, ID> R queryWithMutex(String keyPrefix, ID id, Class<R> type,
                                             Function<ID, R> dbFallback, Long time, TimeUnit timeUnit) {
        String key = keyPrefix + id;
        String lockKey = LOCK_SHOP_KEY + id;
        for (int attempt = 0; attempt < MUTEX_RETRY_LIMIT; attempt++) {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(json)) {
                return JSONUtil.toBean(json, type);
            }
            if (json != null) {
                return null;
            }

            String lockToken = tryLock(lockKey);
            if (lockToken != null) {
                try {
                    // 获取锁后再次读取，避免前一个持锁线程刚完成重建时重复访问数据库。
                    json = stringRedisTemplate.opsForValue().get(key);
                    if (StrUtil.isNotBlank(json)) {
                        return JSONUtil.toBean(json, type);
                    }
                    if (json != null) {
                        return null;
                    }
                    R value = dbFallback.apply(id);
                    if (value == null) {
                        stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                        return null;
                    }
                    this.set(key, value, addTtlJitter(time), timeUnit);
                    return value;
                } finally {
                    unlock(lockKey, lockToken);
                }
            }

            try {
                Thread.sleep(MUTEX_RETRY_INTERVAL_MS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for cache rebuild lock", interrupted);
            }
        }
        throw new IllegalStateException("Timed out while waiting for cache rebuild lock: " + lockKey);
    }

    private long addTtlJitter(Long time) {
        long safeTime = Math.max(1L, time);
        long maxJitter = Math.max(2L, safeTime / 10L + 1L);
        return safeTime + java.util.concurrent.ThreadLocalRandom.current().nextLong(1L, maxJitter);
    }

    private String tryLock(String key) {
        String token = UUID.randomUUID().toString();
        Boolean acquired = stringRedisTemplate.opsForValue().setIfAbsent(key, token, 10, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(acquired) ? token : null;
    }

    private void unlock(String key, String token) {
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
    }
}
