package com.yuelin.service;

import com.yuelin.entity.SeckillVoucher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.yuelin.utils.RedisConstants.SECKILL_RESERVATION_PENDING_COUNT_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeckillStockAuditServiceTest {

    @Mock private ISeckillVoucherService seckillVoucherService;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOperations;
    @Mock private HashOperations<String, Object, Object> hashOperations;
    @InjectMocks private SeckillStockAuditService auditService;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
    }

    @Test
    void reportsStockThatViolatesPendingReservationInvariant() {
        SeckillVoucher voucher = voucher(3L, 8);
        when(seckillVoucherService.list()).thenReturn(Collections.singletonList(voucher));
        when(valueOperations.get("seckill:stock:3")).thenReturn("5");
        when(hashOperations.get(SECKILL_RESERVATION_PENDING_COUNT_KEY, "3")).thenReturn("2");

        List<SeckillStockAuditService.StockDiscrepancy> result = auditService.findDiscrepancies();

        assertEquals(1, result.size());
        assertEquals(6, result.get(0).getExpectedRedisStock());
        assertEquals(5, result.get(0).getActualRedisStock());
    }

    @Test
    void acceptsConsistentDatabaseRedisAndPendingStock() {
        SeckillVoucher voucher = voucher(3L, 8);
        when(seckillVoucherService.list()).thenReturn(Collections.singletonList(voucher));
        when(valueOperations.get("seckill:stock:3")).thenReturn("6");
        when(hashOperations.get(SECKILL_RESERVATION_PENDING_COUNT_KEY, "3")).thenReturn("2");

        assertTrue(auditService.findDiscrepancies().isEmpty());
    }

    @Test
    void atomicallyRecoversMissingKeyOnlyAfterFreshDatabaseReadWithNoPending() {
        SeckillVoucher stale = voucher(3L, 8);
        SeckillVoucher latest = voucher(3L, 5);
        when(seckillVoucherService.list()).thenReturn(Collections.singletonList(stale));
        when(seckillVoucherService.getById(3L)).thenReturn(latest);
        when(valueOperations.get("seckill:stock:3")).thenReturn(null);
        when(redisTemplate.execute(any(DefaultRedisScript.class),
                eq(Arrays.asList("seckill:stock:3", SECKILL_RESERVATION_PENDING_COUNT_KEY)),
                eq("3"), eq("5"))).thenReturn(1L);

        assertTrue(auditService.findDiscrepancies().isEmpty());

        verify(seckillVoucherService).getById(3L);
        verify(redisTemplate).execute(any(DefaultRedisScript.class),
                eq(Arrays.asList("seckill:stock:3", SECKILL_RESERVATION_PENDING_COUNT_KEY)),
                eq("3"), eq("5"));
    }

    @Test
    void missingKeyWithPendingReservationsIsReportedAndNeverRecreated() {
        when(seckillVoucherService.list()).thenReturn(Collections.singletonList(voucher(3L, 8)));
        when(valueOperations.get("seckill:stock:3")).thenReturn(null);
        when(hashOperations.get(SECKILL_RESERVATION_PENDING_COUNT_KEY, "3")).thenReturn("2");

        List<SeckillStockAuditService.StockDiscrepancy> result = auditService.findDiscrepancies();

        assertEquals(1, result.size());
        assertEquals(Integer.MIN_VALUE, result.get(0).getActualRedisStock());
        verify(seckillVoucherService, never()).getById(anyLong());
        verify(redisTemplate, never()).execute(any(DefaultRedisScript.class), anyList(), anyString(), anyString());
    }

    private SeckillVoucher voucher(long id, int stock) {
        return new SeckillVoucher().setVoucherId(id).setStock(stock);
    }
}
