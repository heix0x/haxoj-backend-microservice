package com.hax.haxojbackend.constant;

/**
 * 消息队列常量。
 *
 * <p>集中放这里的原因：生产方（question-service）和消费方（judge-service）
 * 必须用**完全一致**的交换机、队列、路由键；写死两份字符串，改一处忘一处就会出现
 * 「消息发出去了，但没人收」的经典故障。</p>
 */
public interface MqConstants {

    /** 判题业务交换机（direct） */
    String JUDGE_EXCHANGE = "haxoj.judge.exchange";

    /** 判题队列 */
    String JUDGE_QUEUE = "haxoj.judge.queue";

    /** 判题路由键 */
    String JUDGE_ROUTING_KEY = "judge.submit";

    /** 死信交换机 */
    String JUDGE_DLX = "haxoj.judge.dlx";

    /** 死信队列 */
    String JUDGE_DLQ = "haxoj.judge.dlq";

    /** 死信路由键 */
    String JUDGE_DLQ_ROUTING_KEY = "judge.dlq";
}
