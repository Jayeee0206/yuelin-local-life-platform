package com.yuelin.service;

import com.yuelin.entity.VoucherOrder;
import com.yuelin.messaging.MQSender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;
import java.util.Optional;

/**
 * Recovers reservations left pending by process crashes, uncertain confirms or consumer failures.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "yuelin.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SeckillReservationReconciler {

    @Resource
    private SeckillReservationService reservationService;

    @Resource
    private IVoucherOrderService voucherOrderService;

    @Resource
    private MQSender mqSender;

    @Value("${yuelin.seckill.reconcile.timeout-ms:60000}")
    private long timeoutMillis;

    @Value("${yuelin.seckill.reconcile.batch-size:100}")
    private int batchSize;

    @Value("${yuelin.seckill.reconcile.max-retries:3}")
    private int maxRetries;

    @Scheduled(
            initialDelayString = "${yuelin.seckill.reconcile.initial-delay-ms:30000}",
            fixedDelayString = "${yuelin.seckill.reconcile.interval-ms:30000}")
    public void reconcile() {
        long now = System.currentTimeMillis();
        long cutoff = now - timeoutMillis;
        List<Long> timedOutOrderIds = reservationService.findTimedOutOrderIds(cutoff, batchSize);
        for (Long orderId : timedOutOrderIds) {
            try { reconcileOne(orderId, cutoff, now); }
            catch (RuntimeException failure) { log.error("Reservation reconciliation failed for {}", orderId, failure); }
        }
    }

    void reconcileOne(Long orderId, long cutoffEpochMillis, long nextCheckEpochMillis) {
        Optional<VoucherOrder> pending = reservationService.findPendingOrder(orderId);
        if (!pending.isPresent()) {
            return;
        }
        VoucherOrder order = pending.get();

        if (voucherOrderService.getById(orderId) != null) {
            reservationService.complete(order);
            return;
        }

        long attempt = reservationService.claimRetry(orderId, cutoffEpochMillis, nextCheckEpochMillis);
        if (attempt <= 0) {
            return;
        }
        if (attempt > maxRetries) {
            reservationService.compensate(order, "reconciliation retry limit exceeded");
            return;
        }

        try {
            mqSender.sendSeckillMessage(order);
            log.warn("Republished timed-out seckill reservation: orderId={}, attempt={}", orderId, attempt);
        } catch (RuntimeException publishFailure) {
            // MQSender already performs idempotent compensation for a definitive synchronous failure.
            log.error("Failed to republish timed-out seckill reservation: orderId={}", orderId, publishFailure);
        }
    }
}
