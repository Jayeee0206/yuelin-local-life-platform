package com.yuelin.service;

import com.yuelin.entity.Shop;
import com.yuelin.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static com.yuelin.utils.RedisConstants.SHOP_GEO_KEY;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShopGeoIndexServiceTest {
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ShopMapper shopMapper;
    private ShopGeoIndexService service;

    @BeforeEach
    void setUp() {
        service = new ShopGeoIndexService();
        ReflectionTestUtils.setField(service, "redisTemplate", redisTemplate);
        ReflectionTestUtils.setField(service, "shopMapper", shopMapper);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void productionBeanNameSelectsStringTemplateInsteadOfGenericRedisTemplate() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean("redisTemplate", RedisTemplate.class, () -> mock(RedisTemplate.class));
            context.registerBean("stringRedisTemplate", StringRedisTemplate.class, () -> redisTemplate);
            context.registerBean("shopMapper", ShopMapper.class, () -> shopMapper);
            context.registerBean(ShopGeoIndexService.class);
            context.refresh();

            ShopGeoIndexService bean = context.getBean(ShopGeoIndexService.class);
            assertSame(redisTemplate, ReflectionTestUtils.getField(bean, "redisTemplate"));
        }
    }

    @Test
    void typeChangeUsesOneAtomicScriptForPreviousRemovalAndNewPosition() {
        Shop shop = new Shop().setId(9L).setTypeId(3L).setX(120.5).setY(30.2);

        service.syncAfterCommit(shop, 2L);

        verify(redisTemplate).execute(any(DefaultRedisScript.class),
                eq(Arrays.asList(SHOP_GEO_KEY + 2L, SHOP_GEO_KEY + 3L)),
                eq("9"), eq("120.5"), eq("30.2"), eq("1"));
    }

    @Test
    void indexFailureNeverEscapesAfterCommittedDatabaseWrite() {
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("offline"));
        Shop shop = new Shop().setId(9L).setTypeId(3L).setX(120.5).setY(30.2);

        assertDoesNotThrow(() -> service.syncAfterCommit(shop, 2L));
    }

    @Test
    void rebuildUsesTemporaryKeyAndAtomicReplacementScript() {
        when(shopMapper.selectList(any())).thenReturn(Arrays.asList(
                new Shop().setId(1L).setTypeId(3L).setX(120D).setY(30D),
                new Shop().setId(2L).setTypeId(3L).setX(121D).setY(31D)));
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList(),
                eq("1"), eq("120.0"), eq("30.0"), eq("2"), eq("121.0"), eq("31.0")))
                .thenReturn(2L);

        assertEquals(2L, service.rebuildType(3L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redisTemplate).execute(any(DefaultRedisScript.class), keys.capture(),
                eq("1"), eq("120.0"), eq("30.0"), eq("2"), eq("121.0"), eq("31.0"));
        assertEquals(SHOP_GEO_KEY + 3L, keys.getValue().get(0));
        assertTrue(keys.getValue().get(1).startsWith(SHOP_GEO_KEY + "3:rebuild:"));
    }
}
