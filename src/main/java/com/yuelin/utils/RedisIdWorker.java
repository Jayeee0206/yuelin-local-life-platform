package com.yuelin.utils;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

//生成全局唯一ID
@Component
public class RedisIdWorker {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    // Fixed epoch used to keep generated IDs stable across deployments.
    public static final Long BEGIN_TIMESTAMP = 1640995200L;
    //序列号长度
    public static final Long COUNT_BIT = 32L;

    public long nextId(String keyPrefix){
        //1. 生成时间戳
        LocalDateTime now = LocalDateTime.now();
        long currentSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timeStamp = currentSecond - BEGIN_TIMESTAMP;
        //2. 生成序列号
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));
        Long sequence = stringRedisTemplate.opsForValue().increment("inc:" + keyPrefix + ":" + date);
        if (sequence == null) {
            throw new IllegalStateException("Redis did not return an ID sequence");
        }
        if (sequence > 0xFFFFFFFFL) {
            throw new IllegalStateException("Daily ID sequence exhausted for " + keyPrefix);
        }
        //3. 拼接并返回，简单位运算
        return timeStamp << COUNT_BIT | sequence;
    }
}
