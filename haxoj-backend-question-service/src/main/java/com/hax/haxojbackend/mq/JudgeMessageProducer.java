package com.hax.haxojbackend.mq;

import com.hax.haxojbackend.constant.MqConstants;
import com.hax.haxojbackend.model.dto.judge.JudgeMessage;

import javax.annotation.Resource;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * 判题消息生产者。
 *
 * <p>只负责「把消息发出去」，不关心谁消费、消费得多慢。
 * 发送失败会通过 RabbitTemplate 的 confirm/returns 回调记录日志。</p>
 */
@Slf4j
@Component
public class JudgeMessageProducer {

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送判题任务。
     *
     * @param questionSubmitId 提交记录 id（消息体只带 id，消费方回查）
     */
    public void sendJudgeMessage(Long questionSubmitId) {
        JudgeMessage message = JudgeMessage.builder()
                .questionSubmitId(questionSubmitId)
                .timestamp(System.currentTimeMillis())
                .build();
        rabbitTemplate.convertAndSend(
                MqConstants.JUDGE_EXCHANGE,
                MqConstants.JUDGE_ROUTING_KEY,
                message);
        log.info("已发送判题消息: submitId={}", questionSubmitId);
    }
}
