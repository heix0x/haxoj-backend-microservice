package com.hax.haxojbackend.judge;

import cn.hutool.json.JSONUtil;
import com.hax.haxojbackend.common.ErrorCode;
import com.hax.haxojbackend.exception.BusinessException;
import com.hax.haxojbackend.exception.SystemException;
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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
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

        // 幂等：只有「等待中」的提交才需要判题；重复消息、已判过的直接跳过
        if (!QuestionSubmitStatusEnum.WAITING.getValue().equals(questionSubmit.getStatus())) {
            log.info("提交 {} 当前状态为 {}，跳过判题", questionSubmitId, questionSubmit.getStatus());
            return questionSubmit;
        }

        // 原子抢占：把状态从「等待中」改为「判题中」。
        // 只有改成功的那一个消费者能继续，避免重复消费把同一份提交判两次。
        boolean claimed = questionFeignClient.claimQuestionSubmit(questionSubmitId);
        if (!claimed) {
            log.info("提交 {} 已被其他消费者抢占，跳过判题", questionSubmitId);
            return questionSubmit;
        }

        QuestionSubmit questionSubmitUpdate;

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

        JudgeInfo judgeInfo;
        if (executeCodeResponse.getStatus() != null && executeCodeResponse.getStatus() != 1) {
            // 沙箱没跑成功（2=平台故障 / 3=用户代码有问题）。
            // 这时 outputList 是空的，如果交给判题策略，它只会比「输出条数 ≠ 用例条数」
            // 然后判成「答案错误」—— 这是错的：不是答案错，是代码压根没跑起来。
            // 所以在这里直接定性，不进策略。
            judgeInfo = buildFailureJudgeInfo(executeCodeResponse);
        } else {
            JudgeContext judgeContext = new JudgeContext();
            judgeContext.setJudgeInfo(executeCodeResponse.getJudgeInfo());
            judgeContext.setQuestion(question);
            judgeContext.setQuestionSubmit(questionSubmit);
            judgeContext.setInputList(inputList);
            judgeContext.setOutputList(executeCodeResponse.getOutputList());
            judgeContext.setJudgeCaseList(judgeCaseList);

            JudgeManager judgeManager = new JudgeManager();
            judgeInfo = judgeManager.doJudge(judgeContext);
        }

        // 根据判题结果决定提交状态：Accepted → 成功，其他（答案错误/超时/超内存等）→ 失败
        QuestionSubmitStatusEnum statusEnum = JudgeInfoMessageEnum.ACCEPTED.getValue().equals(judgeInfo.getMessage())
                ? QuestionSubmitStatusEnum.SUCCEED
                : QuestionSubmitStatusEnum.FAILED;

        // 修改本次提交记录的状态和判题信息
        questionSubmitUpdate = new QuestionSubmit();
        questionSubmitUpdate.setId(questionSubmitId);
        questionSubmitUpdate.setStatus(statusEnum.getValue());
        questionSubmitUpdate.setJudgeInfo(JSONUtil.toJsonStr(judgeInfo));
        boolean updateResult = questionFeignClient.updateQuestionSubmitById(questionSubmitUpdate);
        if (!updateResult) {
            // 系统异常：回写失败，换个时间重试可能成功 → 应进死信，不能当业务异常丢弃
            throw new SystemException(ErrorCode.OPERATION_ERROR, "题目状态更新错误");
        }

        // 判题通过时，题目的通过数 +1
        if (QuestionSubmitStatusEnum.SUCCEED.equals(statusEnum)) {
            // 原单体代码用 UpdateWrapper 直接更新 question 表；那张表属于 question-service，
            // 重构后改成「具名方法」：调用方只传 questionId，原子更新（acceptedNum = acceptedNum + 1）
            // 由 question-service 自己执行，既避免并发丢更新，又不让判题服务碰别人的库
            boolean acceptedUpdateResult = questionFeignClient.updateQuestionAcceptedNum(questionId);
            if (!acceptedUpdateResult) {
                // 系统异常：此时判题结果已写入，只是通过数没加上 —— 必须重试，不能静默丢弃
                throw new SystemException(ErrorCode.OPERATION_ERROR, "更新题目通过数失败");
            }
        }

        return questionSubmitUpdate;

    }

    /**
     * 沙箱没跑成功时的定性。
     *
     * <p>沙箱只有三个状态：<b>1</b> = 跑完了、<b>2</b> = 平台故障、<b>3</b> = 用户代码有问题。
     * 判题策略看不到这个状态（它只拿到 outputList），所以必须在这里先定性 ——
     * 否则「编译不过」会被当成「答案错误」，用户会去改算法，而实际该补的是 import。</p>
     *
     * <p>状态 3 里还混着编译失败、运行时异常、超时三种，这里按 message 特征再分一次：
     * 超时是沙箱自己写死的文案，能可靠匹配；剩下的统一归「编译错误」——
     * 因为沙箱协议只有三值，要精确区分得扩展协议。</p>
     */
    private JudgeInfo buildFailureJudgeInfo(ExecuteCodeResponse response) {
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMemory(0L);
        judgeInfo.setTime(0L);
        Integer status = response.getStatus();
        String message = response.getMessage();
        if (status != null && status == 2) {
            // 平台故障：跟用户的代码没关系，重试或找管理员
            judgeInfo.setMessage(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue());
        } else if (message != null && message.contains("执行超时")) {
            judgeInfo.setMessage(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue());
        } else {
            judgeInfo.setMessage(JudgeInfoMessageEnum.COMPILE_ERROR.getValue());
        }
        return judgeInfo;
    }

}
