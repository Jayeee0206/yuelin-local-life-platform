package com.yuelin.messaging;

import cn.hutool.json.JSONUtil;
import com.yuelin.entity.VoucherOrder;
import com.yuelin.service.SeckillReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MQSenderTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private SeckillReservationService reservationService;

    @InjectMocks
    private MQSender sender;

    @BeforeEach
    void configure() {
        sender.configureCallbacks();
    }

    @Test
    void publisherNackCompensatesReservation() {
        VoucherOrder order = order();
        ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
        sender.sendSeckillMessage(order);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString(), correlation.capture());

        sender.confirm(correlation.getValue(), false, "broker rejected message");

        verify(reservationService).compensate(eq(order), contains("publisher nack"));
    }

    @Test
    void publisherAckKeepsReservationUntilConsumerCompletesIt() {
        VoucherOrder order = order();
        ArgumentCaptor<CorrelationData> correlation = ArgumentCaptor.forClass(CorrelationData.class);
        sender.sendSeckillMessage(order);
        verify(rabbitTemplate).convertAndSend(anyString(), anyString(), anyString(), correlation.capture());

        sender.confirm(correlation.getValue(), true, null);

        verify(reservationService, never()).compensate(any(), anyString());
    }

    @Test
    void returnedUnroutableMessageCompensatesReservation() {
        VoucherOrder order = order();
        Message message = new Message(
                JSONUtil.toJsonStr(order).getBytes(StandardCharsets.UTF_8),
                new MessageProperties());
        ReturnedMessage returned = new ReturnedMessage(
                message, 312, "NO_ROUTE", "seckill.topic", "missing.route");

        sender.returnedMessage(returned);

        ArgumentCaptor<VoucherOrder> compensatedOrder = ArgumentCaptor.forClass(VoucherOrder.class);
        verify(reservationService).compensate(compensatedOrder.capture(),
                contains("replyCode=312, replyText=NO_ROUTE"));
        assertEquals(order.getId(), compensatedOrder.getValue().getId());
        assertEquals(order.getUserId(), compensatedOrder.getValue().getUserId());
        assertEquals(order.getVoucherId(), compensatedOrder.getValue().getVoucherId());
    }

    @Test
    void malformedReturnedMessageDoesNotCompensateUnknownOrder() {
        Message message = new Message("not-json".getBytes(StandardCharsets.UTF_8), new MessageProperties());
        ReturnedMessage returned = new ReturnedMessage(
                message, 312, "NO_ROUTE", "seckill.topic", "missing.route");

        sender.returnedMessage(returned);

        verify(reservationService, never()).compensate(any(), anyString());
    }

    @Test
    void synchronousPublishFailureCompensatesAndPropagates() {
        VoucherOrder order = order();
        doThrow(new IllegalStateException("connection refused"))
                .when(rabbitTemplate)
                .convertAndSend(anyString(), anyString(), anyString(), any(CorrelationData.class));

        assertThrows(IllegalStateException.class, () -> sender.sendSeckillMessage(order));

        verify(reservationService).compensate(eq(order), contains("publisher exception"));
    }

    private VoucherOrder order() {
        return new VoucherOrder().setId(1001L).setUserId(7L).setVoucherId(3L);
    }
}
