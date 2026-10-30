package com.hax.haxojbackend.judge.codesandbox.impl;

import com.hax.haxojbackend.judge.codesandbox.CodeSandbox;
import com.hax.haxojbackend.judge.codesandbox.model.ExecuteCodeRequest;
import com.hax.haxojbackend.judge.codesandbox.model.ExecuteCodeResponse;
import com.hax.haxojbackend.model.dto.judge.JudgeInfo;
import com.hax.haxojbackend.model.enums.JudgeInfoMessageEnum;
import com.hax.haxojbackend.model.enums.QuestionSubmitStatusEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;


/**
 * 示例代码沙箱（仅为了跑通业务流程）
 *
 * <p>bean 名 {@code example} 就是 {@link com.hax.haxojbackend.judge.codesandbox.CodeSandboxFactory}
 * 查表用的 key，与 {@code codesandbox.type} 的取值一致。</p>
 */
@Slf4j
@Component("example")
public class ExampleCodeSandbox implements CodeSandbox {

    @Override
    public ExecuteCodeResponse executeCode(ExecuteCodeRequest executeCodeRequest) {
        List<String> inputList = executeCodeRequest.getInputList();
        ExecuteCodeResponse executeCodeResponse = new ExecuteCodeResponse();
        executeCodeResponse.setOutputList(inputList);
        executeCodeResponse.setMessage("测试执行成功");
        executeCodeResponse.setStatus(QuestionSubmitStatusEnum.SUCCEED.getValue());
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage(JudgeInfoMessageEnum.ACCEPTED.getText());
        judgeInfo.setMemory(100L);
        judgeInfo.setTime(100L);
        executeCodeResponse.setJudgeInfo(judgeInfo);
        return executeCodeResponse;
    }

}
