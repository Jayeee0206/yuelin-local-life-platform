package com.yuelin.service.impl;

import com.yuelin.dto.Result;
import com.yuelin.entity.SeckillVoucher;
import com.yuelin.entity.Voucher;
import com.yuelin.mapper.VoucherMapper;
import com.yuelin.service.ISeckillVoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VoucherServiceImplTest {

    @Mock
    private VoucherMapper voucherMapper;

    @Mock
    private ISeckillVoucherService seckillVoucherService;

    private VoucherServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new VoucherServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", voucherMapper);
        ReflectionTestUtils.setField(service, "seckillVoucherService", seckillVoucherService);
    }

    @Test
    void ordinaryVoucherDoesNotRequireSeckillTableData() {
        Voucher voucher = new Voucher().setId(1L).setShopId(1L).setType(0).setStatus(1);
        when(voucherMapper.selectList(any())).thenReturn(Collections.singletonList(voucher));

        Result result = service.queryVoucherOfShop(1L);

        List<Voucher> vouchers = vouchers(result);
        assertEquals(1, vouchers.size());
        assertNull(vouchers.get(0).getStock());
        verify(seckillVoucherService, never()).listByIds(any());
    }

    @Test
    void seckillVoucherIsEnrichedInOneBatch() {
        Voucher voucher = new Voucher().setId(2L).setShopId(1L).setType(1).setStatus(1);
        LocalDateTime begin = LocalDateTime.now().minusMinutes(1);
        LocalDateTime end = LocalDateTime.now().plusMinutes(5);
        SeckillVoucher seckill = new SeckillVoucher()
                .setVoucherId(2L)
                .setStock(8)
                .setBeginTime(begin)
                .setEndTime(end);
        when(voucherMapper.selectList(any())).thenReturn(Collections.singletonList(voucher));
        when(seckillVoucherService.listByIds(Collections.singletonList(2L)))
                .thenReturn(Collections.singletonList(seckill));

        Result result = service.queryVoucherOfShop(1L);

        Voucher enriched = vouchers(result).get(0);
        assertEquals(8, enriched.getStock());
        assertEquals(begin, enriched.getBeginTime());
        assertEquals(end, enriched.getEndTime());
    }

    @SuppressWarnings("unchecked")
    private List<Voucher> vouchers(Result result) {
        return (List<Voucher>) result.getData();
    }
}
