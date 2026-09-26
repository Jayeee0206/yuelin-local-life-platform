package com.yuelin.messaging;

import cn.hutool.json.JSONUtil;
import com.yuelin.config.RabbitMQTopicConfig;
import com.yuelin.entity.VoucherOrder;
import com.yuelin.service.IVoucherOrderService;
import com.yuelin.service.OrderResolutionService;
import com.yuelin.service.SeckillReservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

/**
 * Consumes seckill orders. Database transaction and unique index provide idempotency.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "yuelin.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MQReceiver {

    @Resource
    private IVoucherOrderService voucherOrderService;


    @Resource
    private SeckillReservationService reservationService;

    @RabbitListener(queues = RabbitMQTopicConfig.QUEUE)
    public void receiveSeckillMessage(String message) {
        VoucherOrder voucherOrder = JSONUtil.toBean(message, VoucherOrder.class);
        try {
            boolean created = voucherOrderService.createVoucherOrder(voucherOrder);
            if (created) {
                log.info("Created voucher order: orderId={}, userId={}, voucherId={}",
                        voucherOrder.getId(), voucherOrder.getUserId(), voucherOrder.getVoucherId());
                reservationService.complete(voucherOrder);
            } else {
                log.info("Rejected duplicate voucher order reservation: orderId={}, userId={}, voucherId={}",
                        voucherOrder.getId(), voucherOrder.getUserId(), voucherOrder.getVoucherId());
                reservationService.rejectDuplicate(voucherOrder);
            }
        } catch (OrderResolutionService.Cancelled cancelled) {
            reservationService.compensate(voucherOrder, "late delivery after cancellation");
        } catch (DuplicateKeyException duplicate) {
            // Two duplicate messages can pass the pre-check concurrently. The unique index decides the winner.
            log.info("Ignored concurrently duplicated voucher order: orderId={}, userId={}, voucherId={}",
                    voucherOrder.getId(), voucherOrder.getUserId(), voucherOrder.getVoucherId());
            reservationService.rejectDuplicate(voucherOrder);
        }
    }
}
