package com.yuelin.messaging;

import cn.hutool.json.JSONUtil;
import com.yuelin.entity.VoucherOrder;
import com.yuelin.service.IVoucherOrderService;
import com.yuelin.service.SeckillReservationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MQReceiverTest {

    @Mock
    private IVoucherOrderService voucherOrderService;


    @Mock
    private SeckillReservationService reservationService;

    @InjectMocks
    private MQReceiver receiver;

    @Test
    void duplicateMessageIsAcknowledgedWithoutCreatingAnotherOrder() {
        VoucherOrder order = order();
        when(voucherOrderService.createVoucherOrder(any(VoucherOrder.class))).thenReturn(false);

        assertDoesNotThrow(() -> receiver.receiveSeckillMessage(JSONUtil.toJsonStr(order)));

        verify(voucherOrderService).createVoucherOrder(any(VoucherOrder.class));
        verify(reservationService).rejectDuplicate(any(VoucherOrder.class));
        verify(reservationService, never()).complete(any(VoucherOrder.class));
    }

    @Test
    void concurrentDuplicateRejectedByUniqueIndexIsAcknowledged() {
        VoucherOrder order = order();
        when(voucherOrderService.createVoucherOrder(any(VoucherOrder.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));

        assertDoesNotThrow(() -> receiver.receiveSeckillMessage(JSONUtil.toJsonStr(order)));

        verify(reservationService).rejectDuplicate(any(VoucherOrder.class));
        verify(reservationService, never()).complete(any(VoucherOrder.class));
    }

    @Test
    void transientDatabaseFailureIsPropagatedForRabbitRetry() {
        VoucherOrder order = order();
        when(voucherOrderService.createVoucherOrder(any(VoucherOrder.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> receiver.receiveSeckillMessage(JSONUtil.toJsonStr(order)));
    }

    private VoucherOrder order() {
        return new VoucherOrder().setId(1001L).setUserId(7L).setVoucherId(3L);
    }
}
