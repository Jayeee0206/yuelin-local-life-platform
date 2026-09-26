package com.yuelin.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "yuelin.messaging", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RabbitMQTopicConfig {

    public static final String QUEUE = "yuelin.seckill.order.queue";
    public static final String EXCHANGE = "yuelin.seckill.exchange";
    public static final String ROUTING_KEY = "yuelin.seckill.order";
    public static final String DEAD_LETTER_QUEUE = "yuelin.seckill.order.dlq";
    public static final String DEAD_LETTER_EXCHANGE = "yuelin.seckill.dlx";
    public static final String DEAD_LETTER_ROUTING_KEY = "yuelin.seckill.order.dead";

    @Bean
    public Queue seckillOrderQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean
    public TopicExchange seckillExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Binding seckillOrderBinding(
            @Qualifier("seckillOrderQueue") Queue seckillOrderQueue,
            @Qualifier("seckillExchange") TopicExchange seckillExchange) {
        return BindingBuilder.bind(seckillOrderQueue).to(seckillExchange).with(ROUTING_KEY);
    }

    @Bean
    public Queue seckillDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    public DirectExchange seckillDeadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    public Binding seckillDeadLetterBinding(
            @Qualifier("seckillDeadLetterQueue") Queue seckillDeadLetterQueue,
            @Qualifier("seckillDeadLetterExchange") DirectExchange seckillDeadLetterExchange) {
        return BindingBuilder.bind(seckillDeadLetterQueue)
                .to(seckillDeadLetterExchange)
                .with(DEAD_LETTER_ROUTING_KEY);
    }
}
