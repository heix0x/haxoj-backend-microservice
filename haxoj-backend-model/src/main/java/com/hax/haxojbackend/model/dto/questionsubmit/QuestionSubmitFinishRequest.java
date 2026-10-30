package com.hax.haxojbackend.model.dto.questionsubmit;

import java.io.Serializable;
import lombok.Data;

/**
 * 判题落定请求：把一条提交从「判题中」落定为终态。
 *
 * <p>为什么单独建一个 DTO，而不是直接复用 {@code QuestionSubmit} 实体？
 * 实体里有 code、userId 这些判题侧根本不该碰的字段，用它当请求体等于把
 * 「能改什么」的边界交给调用方；这里只暴露落定必需的三个字段。</p>
 *
 * <p>⚠️ 刻意不含 questionId：通过数要加给哪道题，由 question-service 自己按
 * 提交记录查出来。调用方传什么就加什么的话，传错一个 id 就会把别人的通过数加上去。</p>
 */
@Data
public class QuestionSubmitFinishRequest implements Serializable {

    /**
     * 提交记录 id
     */
    private Long id;

    /**
     * 目标状态：只能是 2（成功）或 3（失败）
     */
    private Integer status;

    /**
     * 判题信息（json 对象），前端直接展示
     */
    private String judgeInfo;

    private static final long serialVersionUID = 1L;
}
