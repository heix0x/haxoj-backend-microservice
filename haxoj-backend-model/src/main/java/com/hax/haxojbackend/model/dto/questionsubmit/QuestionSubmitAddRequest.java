package com.hax.haxojbackend.model.dto.questionsubmit;

import java.io.Serializable;
import lombok.Data;

/**
 * 创建题目提交请求
 */
@Data
public class QuestionSubmitAddRequest implements Serializable {

    /**
     * 编程语言
     */
    private String language;

    /**
     * 用户代码
     */
    private String code;

    /**
     * 题目 id
     */
    private Long questionId;

    private static final long serialVersionUID = 1L;
}
