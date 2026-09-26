package com.yuelin.service.impl;

import com.yuelin.dto.Result;
import com.yuelin.entity.SeckillVoucher;
import com.yuelin.messaging.MQSender;
import com.yuelin.service.ISeckillVoucherService;
import com.yuelin.utils.RedisIdWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherOrderServiceImplTest {

    @Mock
    private RedisIdWorker redisIdWorker;
    @Mock
    private MQSender mqSender;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ISeckillVoucherService seckillVoucherService;
    @InjectMocks
    private VoucherOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "messagingEnabled", true);
        ReflectionTestUtils.setField(service, "requestRatePerSecond", 1000D);
        service.initializeRateLimiter();
    }

    @Test
    void rejectsVoucherBeforeItsBeginTimeWithoutReservingRedisStock() {
        when(seckillVoucherService.getById(1L)).thenReturn(new SeckillVoucher()
                .setVoucherId(1L)
                .setBeginTime(LocalDateTime.now().plusMinutes(5))
                .setEndTime(LocalDateTime.now().plusMinutes(10)));

        Result result = service.seckillVoucher(1L);

        assertFalse(result.getSuccess());
        assertEquals("秒杀尚未开始", result.getErrorMsg());
        verifyNoInteractions(redisTemplate, redisIdWorker, mqSender);
    }

    @Test
    void rejectsExpiredVoucherWithoutReservingRedisStock() {
        when(seckillVoucherService.getById(2L)).thenReturn(new SeckillVoucher()
                .setVoucherId(2L)
                .setBeginTime(LocalDateTime.now().minusMinutes(10))
                .setEndTime(LocalDateTime.now().minusMinutes(5)));

        Result result = service.seckillVoucher(2L);

        assertFalse(result.getSuccess());
        assertEquals("秒杀已经结束", result.getErrorMsg());
        verifyNoInteractions(redisTemplate, redisIdWorker, mqSender);
    }
}
