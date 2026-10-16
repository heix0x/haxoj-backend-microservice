package com.hax.haxojbackend.model.dto.question;

import java.io.Serializable;
import lombok.Data;

/**
 * 判题配置
 */
@Data
public class JudgeConfig implements Serializable {

    /**
     * 时间限制（ms）
     */
    private Long timeLimit;

    /**
     * 内存限制（KB）
     */
    private Long memoryLimit;

    /**
     * 堆栈限制（KB）
     */
    private Long stackLimit;

    private static final long serialVersionUID = 1L;
}
