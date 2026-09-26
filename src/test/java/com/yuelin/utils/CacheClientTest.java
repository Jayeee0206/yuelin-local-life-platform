package com.yuelin.utils;

import cn.hutool.json.JSONUtil;
import com.yuelin.entity.Shop;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.yuelin.utils.RedisConstants.CACHE_NULL_TTL;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CacheClientTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private CacheClient cacheClient;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void queryWithPassThroughReturnsCachedObjectWithoutDatabaseCall() {
        Shop cached = shop(1L, "悦邻咖啡");
        when(valueOperations.get("cache:shop:1")).thenReturn(JSONUtil.toJsonStr(cached));
        AtomicBoolean databaseCalled = new AtomicBoolean(false);

        Shop result = cacheClient.queryWithPassThrough(
                "cache:shop:", 1L, Shop.class,
                id -> {
                    databaseCalled.set(true);
                    return null;
                },
                30L, TimeUnit.MINUTES);

        assertNotNull(result);
        assertEquals("悦邻咖啡", result.getName());
        assertFalse(databaseCalled.get());
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any());
    }

    @Test
    void queryWithPassThroughCachesEmptyStringForMissingData() {
        when(valueOperations.get("cache:shop:404")).thenReturn(null);

        Shop result = cacheClient.queryWithPassThrough(
                "cache:shop:", 404L, Shop.class,
                id -> null,
                30L, TimeUnit.MINUTES);

        assertNull(result);
        verify(valueOperations).set(
                "cache:shop:404", "", CACHE_NULL_TTL, TimeUnit.MINUTES);
    }

    @Test
    void queryWithPassThroughCachesObjectJsonOnceWithTtlJitter() {
        when(valueOperations.get("cache:shop:2")).thenReturn(null);
        Shop databaseShop = shop(2L, "悦邻书店");
        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);

        Shop result = cacheClient.queryWithPassThrough(
                "cache:shop:", 2L, Shop.class,
                id -> databaseShop,
                30L, TimeUnit.MINUTES);

        assertSame(databaseShop, result);
        verify(valueOperations).set(
                eq("cache:shop:2"), jsonCaptor.capture(), ttlCaptor.capture(), eq(TimeUnit.MINUTES));
        Shop cached = JSONUtil.toBean(jsonCaptor.getValue(), Shop.class);
        assertEquals("悦邻书店", cached.getName());
        assertTrue(ttlCaptor.getValue() >= 31L && ttlCaptor.getValue() <= 33L);
    }

    @Test
    void queryWithMutexReleasesOnlyTheLockTokenItAcquired() {
        when(valueOperations.get("cache:shop:3")).thenReturn(null);
        when(valueOperations.setIfAbsent(eq("lock:shop:3"), anyString(), eq(10L), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        Shop databaseShop = shop(3L, "悦邻茶馆");
        ArgumentCaptor<String> tokenCaptor = ArgumentCaptor.forClass(String.class);

        Shop result = cacheClient.queryWithMutex(
                "cache:shop:", 3L, Shop.class, id -> databaseShop, 30L, TimeUnit.MINUTES);

        assertSame(databaseShop, result);
        verify(valueOperations).setIfAbsent(eq("lock:shop:3"), tokenCaptor.capture(), eq(10L), eq(TimeUnit.SECONDS));
        verify(redisTemplate).execute(any(), eq(java.util.Collections.singletonList("lock:shop:3")),
                eq(tokenCaptor.getValue()));
    }

    private Shop shop(Long id, String name) {
        Shop shop = new Shop();
        shop.setId(id);
        shop.setName(name);
        return shop;
    }
}
