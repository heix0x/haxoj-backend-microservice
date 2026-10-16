package com.hax.haxojbackend.model.enums;

import org.apache.commons.lang3.ObjectUtils;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 判题信息消息枚举
 */
public enum JudgeInfoMessageEnum {

    /**
     * 成功
     */
    ACCEPTED("成功", "Accepted"),

    /**
     * 答案错误
     */
    WRONG_ANSWER("答案错误", "Wrong Answer"),

    /**
     * 编译错误
     */
    COMPILE_ERROR("编译错误", "Compile Error"),

    /**
     * 内存溢出
     */
    MEMORY_LIMIT_EXCEEDED("内存溢出", "Memory Limit Exceeded"),

    /**
     * 超时
     */
    TIME_LIMIT_EXCEEDED("超时", "Time Limit Exceeded"),

    /**
     * 展示错误
     */
    PRESENTATION_ERROR("展示错误", "Presentation Error"),

    /**
     * 输出溢出
     */
    OUTPUT_LIMIT_EXCEEDED("输出溢出", "Output Limit Exceeded"),

    /**
     * 等待中
     */
    WAITING("等待中", "Waiting"),

    /**
     * 危险操作
     */
    DANGEROUS_OPERATION("危险操作", "Dangerous Operation"),

    /**
     * 运行错误（用户程序的问题）
     */
    RUNTIME_ERROR("运行错误", "Runtime Error"),

    /**
     * 系统错误（做系统人的问题）
     */
    SYSTEM_ERROR("系统错误", "System Error");

    private final String text;

    private final String value;

    JudgeInfoMessageEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 获取值列表
     *
     * @return
     */
    public static List<String> getValues() {
        return Arrays.stream(values()).map(item -> item.value).collect(Collectors.toList());
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value
     * @return
     */
    public static JudgeInfoMessageEnum getEnumByValue(String value) {
        if (ObjectUtils.isEmpty(value)) {
            return null;
        }
        for (JudgeInfoMessageEnum anEnum : JudgeInfoMessageEnum.values()) {
            if (anEnum.value.equals(value)) {
                return anEnum;
            }
        }
        return null;
    }

    public String getValue() {
        return value;
    }

    public String getText() {
        return text;
    }
}
