package com.hax.haxojbackend.judge;

import cn.hutool.json.JSONUtil;
import com.hax.haxojbackend.common.ErrorCode;
import com.hax.haxojbackend.exception.BusinessException;
import com.hax.haxojbackend.judge.codesandbox.CodeSandbox;
import com.hax.haxojbackend.judge.codesandbox.CodeSandboxFactory;
import com.hax.haxojbackend.judge.codesandbox.CodeSandboxProxy;
import com.hax.haxojbackend.judge.codesandbox.model.ExecuteCodeRequest;
import com.hax.haxojbackend.judge.codesandbox.model.ExecuteCodeResponse;
import com.hax.haxojbackend.judge.strategy.JudgeContext;
import com.hax.haxojbackend.model.dto.judge.JudgeInfo;
import com.hax.haxojbackend.model.dto.question.JudgeCase;
import com.hax.haxojbackend.model.entity.Question;
import com.hax.haxojbackend.model.entity.QuestionSubmit;
import com.hax.haxojbackend.model.enums.JudgeInfoMessageEnum;
import com.hax.haxojbackend.model.enums.QuestionSubmitStatusEnum;
import com.hax.haxojbackend.service.QuestionFeignClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class JudgeServiceImpl implements JudgeService {

    // 题目与提交记录都归 question-service 管，这里通过 Feign 远程调用
    @Resource
    private QuestionFeignClient questionFeignClient;

    @Value("${codesandbox.type:example}")
    private String type;

    @Override
    public QuestionSubmit doJudge(long questionSubmitId) {

        // 判断提交是否存在
        // 判断题目是否存在
        QuestionSubmit questionSubmit = questionFeignClient.getQuestionSubmitById(questionSubmitId);
        if (questionSubmit == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "提交信息不存在");
        }
        Long questionId = questionSubmit.getQuestionId();
        Question question = questionFeignClient.getQuestionById(questionId);
        if (question == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "题目不存在");
        }

        // 判断提交状态 如果状态为正在判断则跳过
        if (questionSubmit.getStatus().equals(QuestionSubmitStatusEnum.RUNNING.getValue())) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "题目正在判题中");
        }

        // 更改判题（题目提交）的状态为 “判题中”，防止重复执行

        QuestionSubmit questionSubmitUpdate = new QuestionSubmit();
        questionSubmitUpdate.setId(questionSubmitId);
        questionSubmitUpdate.setStatus(QuestionSubmitStatusEnum.RUNNING.getValue());
        boolean updateResult = questionFeignClient.updateQuestionSubmitById(questionSubmitUpdate);
        if (!updateResult) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新提交状态失败");
        }

        // 开始调用沙箱进行判断
        CodeSandbox codeSandbox = CodeSandboxFactory.newInstance(type);
        codeSandbox = new CodeSandboxProxy(codeSandbox);
        ExecuteCodeRequest executeCodeRequest = new ExecuteCodeRequest();
        executeCodeRequest.setCode(questionSubmit.getCode());
        executeCodeRequest.setLanguage(questionSubmit.getLanguage());

        // 获取输入数据
        String judgeCase = question.getJudgeCase();
        List<JudgeCase> judgeCaseList = JSONUtil.toList(judgeCase, JudgeCase.class);
        List<String> inputList = judgeCaseList.stream().map(JudgeCase::getInput).collect(Collectors.toList());
        executeCodeRequest.setInputList(inputList);

        // 拿到结果信息，再调用判题逻辑进行判断
        ExecuteCodeResponse executeCodeResponse = codeSandbox.executeCode(executeCodeRequest);
        JudgeContext judgeContext = new JudgeContext();
        judgeContext.setJudgeInfo(executeCodeResponse.getJudgeInfo());
        judgeContext.setQuestion(question);
        judgeContext.setQuestionSubmit(questionSubmit);
        judgeContext.setInputList(inputList);
        judgeContext.setOutputList(executeCodeResponse.getOutputList());
        judgeContext.setJudgeCaseList(judgeCaseList);

        JudgeManager judgeManager = new JudgeManager();
        JudgeInfo judgeInfo = judgeManager.doJudge(judgeContext);

        // 根据判题结果决定提交状态：Accepted → 成功，其他（答案错误/超时/超内存等）→ 失败
        QuestionSubmitStatusEnum statusEnum = JudgeInfoMessageEnum.ACCEPTED.getValue().equals(judgeInfo.getMessage())
                ? QuestionSubmitStatusEnum.SUCCEED
                : QuestionSubmitStatusEnum.FAILED;

        // 修改本次提交记录的状态和判题信息
        questionSubmitUpdate = new QuestionSubmit();
        questionSubmitUpdate.setId(questionSubmitId);
        questionSubmitUpdate.setStatus(statusEnum.getValue());
        questionSubmitUpdate.setJudgeInfo(JSONUtil.toJsonStr(judgeInfo));
        updateResult = questionFeignClient.updateQuestionSubmitById(questionSubmitUpdate);
        if (!updateResult) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "题目状态更新错误");
        }

        // 判题通过时，题目的通过数 +1
        if (QuestionSubmitStatusEnum.SUCCEED.equals(statusEnum)) {
            // 原单体代码用 UpdateWrapper 直接更新 question 表；那张表属于 question-service，
            // 重构后改成「具名方法」：调用方只传 questionId，原子更新（acceptedNum = acceptedNum + 1）
            // 由 question-service 自己执行，既避免并发丢更新，又不让判题服务碰别人的库
            boolean acceptedUpdateResult = questionFeignClient.updateQuestionAcceptedNum(questionId);
            if (!acceptedUpdateResult) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新题目通过数失败");
            }
        }

        return questionSubmitUpdate;

    }

}
