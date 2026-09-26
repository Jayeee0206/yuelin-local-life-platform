package com.yuelin.service.impl;

import cn.hutool.json.JSONUtil;
import com.yuelin.dto.Result;
import com.yuelin.entity.ShopType;
import com.yuelin.mapper.ShopTypeMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.yuelin.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopTypeServiceImplTest {

    @Mock
    private ShopTypeMapper shopTypeMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ListOperations<String, String> listOperations;

    private ShopTypeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShopTypeServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", shopTypeMapper);
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redisTemplate);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
    }

    @Test
    void redisFailureFallsBackToSortedDatabaseValues() {
        ShopType first = new ShopType().setId(1L).setName("美食").setSort(1);
        when(listOperations.range(CACHE_SHOP_TYPE_KEY, 0, -1))
                .thenThrow(new IllegalStateException("redis unavailable"));
        when(shopTypeMapper.selectList(any())).thenReturn(Collections.singletonList(first));

        Result result = service.queryList();

        assertTrue(result.getSuccess());
        assertEquals(Collections.singletonList(first), result.getData());
        verify(shopTypeMapper).selectList(any());
    }

    @Test
    void cacheMissUsesOneAtomicFillAndPreservesDatabaseSortOrder() {
        ShopType first = new ShopType().setId(1L).setName("美食").setSort(1);
        ShopType second = new ShopType().setId(2L).setName("休闲").setSort(2);
        when(listOperations.range(CACHE_SHOP_TYPE_KEY, 0, -1)).thenReturn(null);
        when(shopTypeMapper.selectList(any())).thenReturn(Arrays.asList(first, second));

        Result result = service.queryList();

        assertTrue(result.getSuccess());
        @SuppressWarnings("unchecked")
        List<ShopType> returned = (List<ShopType>) result.getData();
        assertEquals(Arrays.asList(first, second), returned);

        ArgumentCaptor<String> values = ArgumentCaptor.forClass(String.class);
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(Collections.singletonList(CACHE_SHOP_TYPE_KEY)),
                values.capture(), values.capture());
        List<String> cached = values.getAllValues();
        assertEquals(1L, JSONUtil.toBean(cached.get(0), ShopType.class).getId());
        assertEquals(2L, JSONUtil.toBean(cached.get(1), ShopType.class).getId());
    }
}
