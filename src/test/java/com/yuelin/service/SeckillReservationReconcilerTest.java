package com.yuelin.service;

import com.yuelin.entity.VoucherOrder;
import com.yuelin.messaging.MQSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SeckillReservationReconcilerTest {

    @Mock
    private SeckillReservationService reservationService;

    @Mock
    private IVoucherOrderService voucherOrderService;

    @Mock
    private MQSender mqSender;

    @InjectMocks
    private SeckillReservationReconciler reconciler;

    @BeforeEach
    void configureRetryLimit() {
        ReflectionTestUtils.setField(reconciler, "maxRetries", 3);
    }

    @Test
    void completedDatabaseOrderClearsPendingReservation() {
        VoucherOrder order = order();
        when(reservationService.findPendingOrder(order.getId())).thenReturn(Optional.of(order));
        when(voucherOrderService.getById(order.getId())).thenReturn(order);

        reconciler.reconcileOne(order.getId(), 100L, 200L);

        verify(reservationService).complete(order);
        verifyNoInteractions(mqSender);
    }

    @Test
    void pendingOrderIsRepublishedWithinRetryLimit() {
        VoucherOrder order = order();
        when(reservationService.findPendingOrder(order.getId())).thenReturn(Optional.of(order));
        when(voucherOrderService.getById(order.getId())).thenReturn(null);
        when(reservationService.claimRetry(order.getId(), 100L, 200L)).thenReturn(2L);

        reconciler.reconcileOne(order.getId(), 100L, 200L);

        verify(mqSender).sendSeckillMessage(order);
        verify(reservationService, never()).compensate(any(), anyString());
    }

    @Test
    void retryLimitCompensatesInsteadOfRepublishing() {
        VoucherOrder order = order();
        when(reservationService.findPendingOrder(order.getId())).thenReturn(Optional.of(order));
        when(voucherOrderService.getById(order.getId())).thenReturn(null);
        when(reservationService.claimRetry(order.getId(), 100L, 200L)).thenReturn(4L);

        reconciler.reconcileOne(order.getId(), 100L, 200L);

        verify(reservationService).compensate(order, "reconciliation retry limit exceeded");
        verifyNoInteractions(mqSender);
    }

    private VoucherOrder order() {
        return new VoucherOrder().setId(1001L).setUserId(7L).setVoucherId(3L);
    }
}
