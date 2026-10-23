package com.hax.haxojbackend.model.dto.judge;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 判题消息体：question-service 发给 judge-service 的「待判题」任务。
 *
 * <p>只放 id，不放代码/题目内容——消息越小越好，消费方按 id 回查最新数据，
 * 避免「消息里的数据已经过期」。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JudgeMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 题目提交记录 id */
    private Long questionSubmitId;

    /** 发送时间戳（毫秒），用于排查延迟 */
    private Long timestamp;
}
