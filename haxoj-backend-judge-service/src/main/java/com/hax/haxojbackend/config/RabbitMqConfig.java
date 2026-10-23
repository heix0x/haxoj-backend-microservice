package com.hax.haxojbackend.config;

import com.hax.haxojbackend.constant.MqConstants;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 判题消息的消费端配置（judge-service）。
 *
 * <p>为什么这里也声明一遍交换机/队列？因为消费方可能比生产方先启动。
 * 声明是幂等的：谁先启动谁创建，参数必须与生产方完全一致，否则 RabbitMQ 会报
 * {@code PRECONDITION_FAILED}（队列已存在但参数不同）。</p>
 */
@Configuration
public class RabbitMqConfig {

    @Bean
    public DirectExchange judgeExchange() {
        return new DirectExchange(MqConstants.JUDGE_EXCHANGE, true, false);
    }

    @Bean
    public Queue judgeQueue() {
        return QueueBuilder.durable(MqConstants.JUDGE_QUEUE)
                .deadLetterExchange(MqConstants.JUDGE_DLX)
                .deadLetterRoutingKey(MqConstants.JUDGE_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding judgeBinding() {
        return BindingBuilder.bind(judgeQueue()).to(judgeExchange()).with(MqConstants.JUDGE_ROUTING_KEY);
    }

    @Bean
    public DirectExchange judgeDlx() {
        return new DirectExchange(MqConstants.JUDGE_DLX, true, false);
    }

    @Bean
    public Queue judgeDlq() {
        return QueueBuilder.durable(MqConstants.JUDGE_DLQ).build();
    }

    @Bean
    public Binding judgeDlqBinding() {
        return BindingBuilder.bind(judgeDlq()).to(judgeDlx()).with(MqConstants.JUDGE_DLQ_ROUTING_KEY);
    }

    /** 与生产方一致的 JSON 转换器（靠消息头里的类型信息还原对象） */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
