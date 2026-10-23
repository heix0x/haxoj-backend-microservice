package com.hax.haxojbackend.config;

import com.hax.haxojbackend.constant.MqConstants;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 判题消息的生产端配置（question-service）。
 *
 * <p>交换机/队列/绑定都在这里声明：应用启动时 RabbitAdmin 会自动创建（幂等），
 * 不需要像原始示例那样单独写一个 main 方法去建。</p>
 */
@Slf4j
@Configuration
public class RabbitMqConfig {

    /** 业务交换机：direct 类型，按 routingKey 精确路由 */
    @Bean
    public DirectExchange judgeExchange() {
        return new DirectExchange(MqConstants.JUDGE_EXCHANGE, true, false);
    }

    /**
     * 判题队列：durable + 绑定死信交换机。
     * 消息被 nack（requeue=false）或过期时，会转到死信队列，而不是直接丢失。
     */
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

    /** 死信交换机 */
    @Bean
    public DirectExchange judgeDlx() {
        return new DirectExchange(MqConstants.JUDGE_DLX, true, false);
    }

    /** 死信队列：人工排查/重放用 */
    @Bean
    public Queue judgeDlq() {
        return QueueBuilder.durable(MqConstants.JUDGE_DLQ).build();
    }

    @Bean
    public Binding judgeDlqBinding() {
        return BindingBuilder.bind(judgeDlq()).to(judgeDlx()).with(MqConstants.JUDGE_DLQ_ROUTING_KEY);
    }

    /** JSON 序列化：消息体是对象，不是字符串拼接 */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 自定义 RabbitTemplate：JSON 转换 + 发送确认 + 无法路由时回报。
     *
     * <p>confirm 只能告诉你「到没到交换机」；returns 告诉你「到了交换机但没路由到队列」。
     * 两者都开，才能发现「routingKey 写错」这类静默失败。</p>
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter messageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(messageConverter);
        template.setMandatory(true);
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("消息未到达交换机: cause={}", cause);
            }
        });
        template.setReturnsCallback(returned -> log.error(
                "消息无法路由到队列: exchange={}, routingKey={}, reply={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        return template;
    }
}
