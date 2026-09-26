package com.yuelin.service.impl;

import com.yuelin.dto.Result;
import com.yuelin.entity.Shop;
import com.yuelin.mapper.ShopMapper;
import com.yuelin.service.ShopCacheVersionService;
import com.yuelin.service.ShopGeoIndexService;
import com.yuelin.utils.CacheClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.yuelin.utils.RedisConstants.CACHE_SHOP_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShopServiceImplTest {

    @Mock
    private ShopMapper shopMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ShopCacheVersionService versions;
    @Mock
    private ShopGeoIndexService geoIndex;
    @Mock
    private CacheClient cache;
    private ShopServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShopServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", shopMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "cacheVersions", versions);
        ReflectionTestUtils.setField(service, "geoIndexService", geoIndex);
        ReflectionTestUtils.setField(service, "cacheClient", cache);
    }

    @Test
    void updateDoesNotReportSuccessWhenShopDoesNotExist() {
        when(shopMapper.selectById(404L)).thenReturn(null);

        Result result = service.update(new Shop().setId(404L).setName("不存在的店铺"));

        assertFalse(result.getSuccess());
        verify(shopMapper, never()).updateById(any());
        verifyNoInteractions(versions, geoIndex);
    }

    @Test
    void successfulUpdateAdvancesVersionAndSynchronizesMergedGeo() {
        Shop before = shop(1L, 2L, 120D, 30D);
        Shop update = new Shop().setId(1L).setName("new").setTypeId(3L).setX(121D);
        when(shopMapper.selectById(1L)).thenReturn(before);
        when(shopMapper.updateById(update)).thenReturn(1);

        assertTrue(service.update(update).getSuccess());

        verify(versions).advance(1L);
        verify(geoIndex).syncAfterCommit(
                argThat(indexed -> indexed.getId().equals(1L)
                        && indexed.getTypeId().equals(3L)
                        && indexed.getX().equals(121D)
                        && indexed.getY().equals(30D)),
                eq(2L));
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void versionFailurePropagatesForTransactionRollbackBeforeGeoScheduling() {
        Shop update = new Shop().setId(1L);
        when(shopMapper.selectById(1L)).thenReturn(shop(1L, 2L, 120D, 30D));
        when(shopMapper.updateById(update)).thenReturn(1);
        doThrow(new IllegalStateException("version write failed")).when(versions).advance(1L);

        assertThrows(IllegalStateException.class, () -> service.update(update));
        verifyNoInteractions(geoIndex);
    }

    @Test
    void createRejectsInvalidCoordinatesBeforeDatabaseWrite() {
        Result result = service.create(shop(null, 2L, 181D, 30D));
        assertFalse(result.getSuccess());
        verifyNoInteractions(shopMapper, geoIndex);
    }

    @Test
    void createSchedulesGeoAfterDatabaseInsert() {
        Shop shop = shop(null, 2L, 120D, 30D).setName("new");
        when(shopMapper.insert(shop)).thenAnswer(invocation -> {
            shop.setId(99L);
            return 1;
        });

        Result result = service.create(shop);

        assertTrue(result.getSuccess());
        assertEquals("99", result.getData());
        verify(geoIndex).syncAfterCommit(shop, null);
    }

    @Test
    void readsOnlyTheCommittedVersionNamespace() {
        when(versions.current(1L)).thenReturn(7L);
        Shop shop = new Shop().setId(1L).setName("current");
        when(cache.queryWithPassThrough(eq(CACHE_SHOP_KEY + "7:"), eq(1L), eq(Shop.class), any(), anyLong(), eq(TimeUnit.MINUTES)))
                .thenReturn(shop);
        assertEquals(shop, service.queryById(1L).getData());
    }

    @Test
    void redisCacheOutageFallsBackToDatabase() {
        when(versions.current(1L)).thenReturn(3L);
        when(cache.queryWithPassThrough(anyString(), eq(1L), eq(Shop.class), any(), anyLong(), eq(TimeUnit.MINUTES)))
                .thenThrow(new RedisConnectionFailureException("offline"));
        Shop shop = new Shop().setId(1L);
        when(shopMapper.selectById(1L)).thenReturn(shop);
        assertEquals(shop, service.queryById(1L).getData());
    }

    @Test
    void redisGeoOutageFallsBackToDistanceSortedDatabasePage() {
        when(redisTemplate.opsForGeo()).thenThrow(new RedisConnectionFailureException("offline"));
        Shop farther = shop(2L, 1L, 120.02D, 30D);
        Shop nearer = shop(1L, 1L, 120.001D, 30D);
        Shop outside = shop(3L, 1L, 121D, 30D);
        when(shopMapper.selectList(any())).thenReturn(Arrays.asList(farther, outside, nearer));

        Result result = service.queryShopByType(1L, 1, 120D, 30D, null);

        assertTrue(result.getSuccess());
        @SuppressWarnings("unchecked")
        List<Shop> shops = (List<Shop>) result.getData();
        assertEquals(Arrays.asList(nearer, farther), shops);
        assertTrue(shops.get(0).getDistance() < shops.get(1).getDistance());
    }

    private Shop shop(Long id, Long typeId, Double x, Double y) {
        return new Shop().setId(id).setTypeId(typeId).setX(x).setY(y);
    }
}
