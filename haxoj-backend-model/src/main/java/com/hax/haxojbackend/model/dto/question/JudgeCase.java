package com.hax.haxojbackend.model.dto.question;

import java.io.Serializable;
import lombok.Data;

/**
 * 判题用例
 */
@Data
public class JudgeCase implements Serializable {

    /**
     * 输入用例
     */
    private String input;

    /**
     * 输出用例
     */
    private String output;

    private static final long serialVersionUID = 1L;
}
