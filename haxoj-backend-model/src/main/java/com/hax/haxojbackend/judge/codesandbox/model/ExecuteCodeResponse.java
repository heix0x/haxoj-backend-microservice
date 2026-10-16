package com.hax.haxojbackend.judge.codesandbox.model;

import com.hax.haxojbackend.model.dto.judge.JudgeInfo;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ExecuteCodeResponse {

    /**
     * 输出列表
     */
    private List<String> outputList;

    /**
     * 执行结果
     */
    private String message;

    /**
     * 执行状态
     */
    private Integer status;

    /**
     * 判断信息
     */
    private JudgeInfo judgeInfo;

}
