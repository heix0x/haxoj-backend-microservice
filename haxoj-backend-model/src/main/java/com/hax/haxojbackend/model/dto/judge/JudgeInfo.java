package com.hax.haxojbackend.model.dto.judge;

import java.io.Serializable;
import lombok.Data;

/**
 * 判题信息
 */
@Data
public class JudgeInfo implements Serializable {

    /**
     * 判题信息（程序消耗的内存、耗时等）
     */
    private String message;

    /**
     * 消耗内存（KB）
     */
    private Long memory;

    /**
     * 耗时（ms）
     */
    private Long time;

    private static final long serialVersionUID = 1L;
}
