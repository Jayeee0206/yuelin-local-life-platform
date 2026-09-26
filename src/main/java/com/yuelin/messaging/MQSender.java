package com.yuelin.messaging;

import cn.hutool.json.JSONUtil;
import com.yuelin.config.RabbitMQTopicConfig;
import com.yuelin.entity.VoucherOrder;
import com.yuelin.service.SeckillReservationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.nio.charset.StandardCharsets;

/**
 * Publishes seckill orders with correlated confirms and mandatory returns.
 */
@Slf4j
@Service
public class MQSender implements RabbitTemplate.ConfirmCallback, RabbitTemplate.ReturnsCallback {

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private SeckillReservationService reservationService;

    @PostConstruct
    public void configureCallbacks() {
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setConfirmCallback(this);
        rabbitTemplate.setReturnsCallback(this);
    }

    public void sendSeckillMessage(VoucherOrder order) {
        SeckillCorrelationData correlationData = new SeckillCorrelationData(order);
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQTopicConfig.EXCHANGE,
                    RabbitMQTopicConfig.ROUTING_KEY,
                    JSONUtil.toJsonStr(order),
                    correlationData);
            log.info("Published seckill order: orderId={}", order.getId());
        } catch (RuntimeException publishFailure) {
            reservationService.compensate(order, "publisher exception: " + publishFailure.getMessage());
            throw publishFailure;
        }
    }

    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        if (ack) {
            return;
        }
        if (correlationData instanceof SeckillCorrelationData) {
            VoucherOrder order = ((SeckillCorrelationData) correlationData).getOrder();
            log.warn("Publisher nack received: orderId={}, cause={}", order.getId(), cause);
            reservationService.compensate(order, "publisher nack: " + cause);
        } else {
            log.error("Publisher nack without seckill correlation data: correlationId={}, cause={}",
                    correlationData == null ? null : correlationData.getId(), cause);
        }
    }

    @Override
    public void returnedMessage(ReturnedMessage returned) {
        try {
            String body = new String(returned.getMessage().getBody(), StandardCharsets.UTF_8);
            VoucherOrder order = JSONUtil.toBean(body, VoucherOrder.class);
            reservationService.compensate(order,
                    "unroutable message: replyCode=" + returned.getReplyCode()
                            + ", replyText=" + returned.getReplyText());
        } catch (RuntimeException parsingFailure) {
            log.error("Unable to compensate returned seckill message", parsingFailure);
        }
    }

    static final class SeckillCorrelationData extends CorrelationData {
        private final VoucherOrder order;

        SeckillCorrelationData(VoucherOrder order) {
            super(order.getId().toString());
            this.order = order;
        }

        VoucherOrder getOrder() {
            return order;
        }
    }
}
