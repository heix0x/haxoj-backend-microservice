package com.hax.haxojbackend.mq;

import cn.hutool.json.JSONUtil;
import com.hax.haxojbackend.constant.MqConstants;
import com.hax.haxojbackend.exception.BusinessException;
import com.hax.haxojbackend.judge.JudgeService;
import com.hax.haxojbackend.model.dto.judge.JudgeInfo;
import com.hax.haxojbackend.model.dto.judge.JudgeMessage;
import com.hax.haxojbackend.model.dto.questionsubmit.QuestionSubmitFinishRequest;
import com.hax.haxojbackend.model.entity.QuestionSubmit;
import com.hax.haxojbackend.model.enums.JudgeInfoMessageEnum;
import com.hax.haxojbackend.model.enums.QuestionSubmitStatusEnum;
import com.hax.haxojbackend.service.QuestionFeignClient;

import java.io.IOException;
import javax.annotation.Resource;

import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * 判题消息消费者。
 *
 * <p>确认策略（手动 ack）：</p>
 * <ul>
 *   <li>判题成功 → ack，消息从队列删除；</li>
 *   <li>业务异常（如提交不存在）→ ack 丢弃，重试也不会成功，避免毒消息卡队列；</li>
 *   <li>系统异常（沙箱挂了、网络抖动）→ 先把状态重置为「等待中」，再 nack 且 requeue=false，
 *       消息进入死信队列，等人工/定时任务重放。</li>
 * </ul>
 *
 * <p>⚠️ 为什么不能 nack 后 requeue=true？那会让同一条消息立刻回到队头，形成死循环，
 * 把消费者 CPU 打满。</p>
 */
@Slf4j
@Component
public class JudgeMessageConsumer {

    @Resource
    private JudgeService judgeService;

    @Resource
    private QuestionFeignClient questionFeignClient;

    @RabbitListener(queues = MqConstants.JUDGE_QUEUE, ackMode = "MANUAL")
    public void onJudgeMessage(JudgeMessage message,
                               Channel channel,
                               @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) throws IOException {
        Long questionSubmitId = message == null ? null : message.getQuestionSubmitId();
        if (questionSubmitId == null) {
            log.warn("收到空消息，直接确认");
            channel.basicAck(deliveryTag, false);
            return;
        }

        try {
            judgeService.doJudge(questionSubmitId);
            channel.basicAck(deliveryTag, false);
            log.info("判题完成并确认: submitId={}", questionSubmitId);
        } catch (BusinessException e) {
            // 业务上不可重试（提交不存在、题目不存在、状态不允许等），但**不能只是 ack**：
            // 异常可能发生在抢占成功之后（状态已是「判题中」），ack 掉消息就再也没人管这条提交，
            // 前端会一直转圈。所以先尽力落定为终态，再 ack。
            log.warn("判题业务异常，落定为失败并确认消息: submitId={}, msg={}", questionSubmitId, e.getMessage());
            markFailed(questionSubmitId);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            // 系统异常：可重试。重置状态后进死信，避免"卡在判题中"
            log.error("判题系统异常，转入死信: submitId={}", questionSubmitId, e);
            resetToWaiting(questionSubmitId);
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * 业务异常时的兜底落定：把提交标成「失败 + 系统错误」，别让它永远停在「判题中」。
     *
     * <p>用 finish 接口而不是直接 updateById：它带 {@code WHERE status=RUNNING} 守卫，
     * 不会把一条已经成功的提交改坏；重复调用也不会重复累加通过数。</p>
     *
     * <p>失败只记日志：消息马上就要 ack 掉了，这里再抛异常只会让消费者重试一条
     * 注定失败的消息。</p>
     */
    private void markFailed(Long questionSubmitId) {
        try {
            JudgeInfo judgeInfo = new JudgeInfo();
            judgeInfo.setMessage(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue());
            judgeInfo.setMemory(0L);
            judgeInfo.setTime(0L);

            QuestionSubmitFinishRequest request = new QuestionSubmitFinishRequest();
            request.setId(questionSubmitId);
            request.setStatus(QuestionSubmitStatusEnum.FAILED.getValue());
            request.setJudgeInfo(JSONUtil.toJsonStr(judgeInfo));

            boolean marked = questionFeignClient.finishQuestionSubmit(request);
            if (!marked) {
                // 提交不存在，或状态既不是「判题中」也不是终态 —— 此时没有任何可做的补救
                log.warn("业务异常落定未生效: submitId={}", questionSubmitId);
            }
        } catch (Exception ex) {
            log.error("业务异常落定失败，提交可能停留在中间态: submitId={}", questionSubmitId, ex);
        }
    }

    /**
     * 把提交状态重置为「等待中」，让死信重放时还能被重新抢占。
     * 重置失败只记录日志：消息已经在死信队列里，人工可以处理。
     */
    private void resetToWaiting(Long questionSubmitId) {
        try {
            QuestionSubmit update = new QuestionSubmit();
            update.setId(questionSubmitId);
            update.setStatus(QuestionSubmitStatusEnum.WAITING.getValue());
            questionFeignClient.updateQuestionSubmitById(update);
        } catch (Exception ex) {
            log.error("重置提交状态失败: submitId={}", questionSubmitId, ex);
        }
    }
}
