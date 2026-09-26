package com.yuelin.config;

import com.yuelin.dto.Result;
import com.yuelin.messaging.MQReceiver;
import com.yuelin.service.SeckillReservationReconciler;
import com.yuelin.service.impl.VoucherOrderServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessagingDisabledModeTest {

    @Test
    void doesNotRegisterRabbitTopologyConsumerOrReconciler() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "messaging-disabled-test",
                    Map.of("yuelin.messaging.enabled", "false")
            ));
            context.register(RabbitMQTopicConfig.class, MQReceiver.class, SeckillReservationReconciler.class);

            context.refresh();

            assertTrue(context.getBeansOfType(RabbitMQTopicConfig.class).isEmpty());
            assertTrue(context.getBeansOfType(MQReceiver.class).isEmpty());
            assertTrue(context.getBeansOfType(SeckillReservationReconciler.class).isEmpty());
        }
    }

    @Test
    void rejectsSeckillBeforeTouchingMessagingDependencies() {
        VoucherOrderServiceImpl service = new VoucherOrderServiceImpl();
        ReflectionTestUtils.setField(service, "messagingEnabled", false);

        Result result = service.seckillVoucher(1L);

        assertFalse(result.getSuccess());
        assertEquals("秒杀消息服务当前未启用", result.getErrorMsg());
    }
}
