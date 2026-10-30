package com.hax.haxojbackend.judge.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.hax.haxojbackend.model.dto.judge.JudgeInfo;
import com.hax.haxojbackend.model.dto.question.JudgeCase;
import com.hax.haxojbackend.model.entity.Question;
import com.hax.haxojbackend.model.entity.QuestionSubmit;
import com.hax.haxojbackend.model.enums.JudgeInfoMessageEnum;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 判题策略的纯逻辑测试：不启 Spring、不连库、不碰沙箱。
 *
 * <p>重点覆盖三类会「给出错误结论」的边界：</p>
 * <ol>
 *   <li>超时宽限的边界值（等于不超、多 1ms 超）；</li>
 *   <li>判题配置缺失 / 限制字段为 null / 沙箱没采到数据；</li>
 *   <li>预期输出为 null（历史上这里会 NPE，直接把提交卡在「判题中」）。</li>
 * </ol>
 */
class AbstractJudgeStrategyTest {

    /** 默认策略：不给任何宽限 */
    private final JudgeStrategy defaultStrategy = new DefaultJudgeStrategy();

    /** Java 策略：宽限 1000ms */
    private final JudgeStrategy javaStrategy = new JavaLanguageJudgeStrategy();

    private static final String LIMITS = "{\"timeLimit\":1000,\"memoryLimit\":262144,\"stackLimit\":131072}";

    @Test
    @DisplayName("输出与用例一致 → 成功")
    void accepted() {
        JudgeContext context = newContext(LIMITS,
                cases(caseOf("1 2", "3")), outputs("3"), 100L, 10240L);

        assertEquals(JudgeInfoMessageEnum.ACCEPTED.getValue(), defaultStrategy.doJudge(context).getMessage());
        assertEquals(JudgeInfoMessageEnum.ACCEPTED.getValue(), javaStrategy.doJudge(context).getMessage());
    }

    @Test
    @DisplayName("输出不一致 → 答案错误")
    void wrongAnswer() {
        JudgeContext context = newContext(LIMITS,
                cases(caseOf("1 2", "3")), outputs("4"), 100L, 10240L);

        assertEquals(JudgeInfoMessageEnum.WRONG_ANSWER.getValue(), defaultStrategy.doJudge(context).getMessage());
    }

    @Test
    @DisplayName("内存超过题目限制 → 内存溢出")
    void memoryLimitExceeded() {
        // 262144KB = 256MB，这里给 300MB
        JudgeContext context = newContext(LIMITS,
                cases(caseOf("1 2", "3")), outputs("3"), 100L, 300L * 1024);

        assertEquals(JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED.getValue(),
                defaultStrategy.doJudge(context).getMessage());
    }

    @Test
    @DisplayName("Java 宽限边界：等于 timeLimit+1000 不算超时，多 1ms 才算")
    void javaTimeAllowanceBoundary() {
        JudgeContext exactlyAtBoundary = newContext(LIMITS,
                cases(caseOf("1 2", "3")), outputs("3"), 2000L, 10240L);
        assertEquals(JudgeInfoMessageEnum.ACCEPTED.getValue(),
                javaStrategy.doJudge(exactlyAtBoundary).getMessage());

        JudgeContext oneMillisecondOver = newContext(LIMITS,
                cases(caseOf("1 2", "3")), outputs("3"), 2001L, 10240L);
        assertEquals(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue(),
                javaStrategy.doJudge(oneMillisecondOver).getMessage());
    }

    @Test
    @DisplayName("默认策略无宽限：超过 timeLimit 即超时")
    void defaultStrategyHasNoAllowance() {
        JudgeContext exactlyAtLimit = newContext(LIMITS,
                cases(caseOf("1 2", "3")), outputs("3"), 1000L, 10240L);
        assertEquals(JudgeInfoMessageEnum.ACCEPTED.getValue(),
                defaultStrategy.doJudge(exactlyAtLimit).getMessage());

        JudgeContext oneMillisecondOver = newContext(LIMITS,
                cases(caseOf("1 2", "3")), outputs("3"), 1001L, 10240L);
        assertEquals(JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED.getValue(),
                defaultStrategy.doJudge(oneMillisecondOver).getMessage());
    }

    @Test
    @DisplayName("判题配置缺失或为空对象 → 跳过资源限制，只看输出")
    void missingJudgeConfigSkipsLimitCheck() {
        JudgeContext nullConfig = newContext(null,
                cases(caseOf("1 2", "3")), outputs("3"), 999999L, 999999L);
        assertEquals(JudgeInfoMessageEnum.ACCEPTED.getValue(),
                defaultStrategy.doJudge(nullConfig).getMessage());

        JudgeContext emptyConfig = newContext("{}",
                cases(caseOf("1 2", "3")), outputs("3"), 999999L, 999999L);
        assertEquals(JudgeInfoMessageEnum.ACCEPTED.getValue(),
                defaultStrategy.doJudge(emptyConfig).getMessage());
    }

    @Test
    @DisplayName("预期输出为 null 时用 Objects.equals 比较，不抛空指针")
    void nullExpectedOutput() {
        JudgeContext bothNull = newContext(LIMITS,
                cases(caseOf("1 2", null)), Collections.singletonList(null), 100L, 10240L);
        assertEquals(JudgeInfoMessageEnum.ACCEPTED.getValue(),
                defaultStrategy.doJudge(bothNull).getMessage());

        JudgeContext actualNotNull = newContext(LIMITS,
                cases(caseOf("1 2", null)), outputs("x"), 100L, 10240L);
        assertEquals(JudgeInfoMessageEnum.WRONG_ANSWER.getValue(),
                defaultStrategy.doJudge(actualNotNull).getMessage());
    }

    @Test
    @DisplayName("沙箱没采到 time/memory（null）→ 按 0 处理，不影响结论")
    void nullTimeAndMemory() {
        JudgeContext context = newContext(LIMITS,
                cases(caseOf("1 2", "3")), outputs("3"), null, null);

        JudgeInfo result = javaStrategy.doJudge(context);
        assertEquals(JudgeInfoMessageEnum.ACCEPTED.getValue(), result.getMessage());
        assertEquals(0L, result.getTime());
        assertEquals(0L, result.getMemory());
    }

    @Test
    @DisplayName("题目没有测试用例 → 系统错误，绝不能放行成「成功」")
    void noJudgeCaseIsSystemError() {
        JudgeContext context = newContext(LIMITS,
                new ArrayList<>(), new ArrayList<>(), 100L, 10240L);

        assertEquals(JudgeInfoMessageEnum.SYSTEM_ERROR.getValue(),
                defaultStrategy.doJudge(context).getMessage());
    }

    // ==================== 构造工具 ====================

    private static JudgeContext newContext(String judgeConfigJson, List<JudgeCase> judgeCaseList,
            List<String> outputList, Long time, Long memory) {
        Question question = new Question();
        question.setId(1L);
        question.setJudgeConfig(judgeConfigJson);

        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setTime(time);
        judgeInfo.setMemory(memory);

        JudgeContext context = new JudgeContext();
        context.setQuestion(question);
        context.setJudgeInfo(judgeInfo);
        context.setJudgeCaseList(judgeCaseList);
        context.setInputList(judgeCaseList.stream().map(JudgeCase::getInput).collect(Collectors.toList()));
        context.setOutputList(outputList);
        context.setQuestionSubmit(new QuestionSubmit());
        return context;
    }

    private static List<JudgeCase> cases(JudgeCase... judgeCases) {
        return new ArrayList<>(Arrays.asList(judgeCases));
    }

    private static List<String> outputs(String... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    private static JudgeCase caseOf(String input, String output) {
        JudgeCase judgeCase = new JudgeCase();
        judgeCase.setInput(input);
        judgeCase.setOutput(output);
        return judgeCase;
    }
}
