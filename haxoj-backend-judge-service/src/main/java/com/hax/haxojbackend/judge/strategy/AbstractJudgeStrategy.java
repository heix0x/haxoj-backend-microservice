package com.hax.haxojbackend.judge.strategy;

import cn.hutool.json.JSONUtil;
import com.hax.haxojbackend.model.dto.judge.JudgeInfo;
import com.hax.haxojbackend.model.dto.question.JudgeCase;
import com.hax.haxojbackend.model.dto.question.JudgeConfig;
import com.hax.haxojbackend.model.entity.Question;
import com.hax.haxojbackend.model.enums.JudgeInfoMessageEnum;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

/**
 * 判题策略的公共骨架：把「拿数据 → 判资源 → 判输出 → 组装结果」这套流程写死在这里。
 *
 * <p>为什么抽这个基类？原来 {@link DefaultJudgeStrategy} 和 {@link JavaLanguageJudgeStrategy}
 * 是同一段逻辑的两份拷贝，改一处忘一处就是两套判题口径；而且两份各自都有
 * memory / memoryLimit / judgeConfig / 预期输出 为 null 时的空指针路径。
 * 现在子类只需要回答一个问题：<b>这门语言要给多少启动宽限</b>。</p>
 *
 * <p><b>判定顺序是刻意的：内存 → 时间 → 输出条数 → 逐项内容。</b>
 * 被超时掐断或被 OOM 杀掉的程序，输出通常是不完整的；如果先比输出，
 * 用户会拿到「答案错误」—— 然后去改算法，而实际该做的是把复杂度降下来。
 * 资源超限是更接近事实的那个结论。</p>
 *
 * <p>关于空值：判题配置缺失、限制字段为 null，一律按「未设置限制」处理并跳过该项，
 * 而不是抛异常或当成 0 —— 把「没配」当成「限制为 0」，会让所有提交都判超时。
 * 沙箱没采到 time/memory 时按 0 参与比较（不影响结论，只是不做超限判定）。</p>
 */
@Slf4j
public abstract class AbstractJudgeStrategy implements JudgeStrategy {

    @Override
    public JudgeInfo doJudge(JudgeContext judgeContext) {
        JudgeInfo sandboxJudgeInfo = judgeContext.getJudgeInfo();
        long memory = sandboxJudgeInfo == null || sandboxJudgeInfo.getMemory() == null
                ? 0L : sandboxJudgeInfo.getMemory();
        long time = sandboxJudgeInfo == null || sandboxJudgeInfo.getTime() == null
                ? 0L : sandboxJudgeInfo.getTime();

        JudgeInfo judgeInfoResponse = new JudgeInfo();
        judgeInfoResponse.setMemory(memory);
        judgeInfoResponse.setTime(time);

        JudgeConfig judgeConfig = parseJudgeConfig(judgeContext.getQuestion());
        if (judgeConfig != null) {
            // ① 内存：沙箱返回 KB，题目 memoryLimit 也是 KB，单位一致，不要在这里换算
            Long memoryLimit = judgeConfig.getMemoryLimit();
            if (memoryLimit != null && memory > memoryLimit) {
                return fail(judgeInfoResponse, JudgeInfoMessageEnum.MEMORY_LIMIT_EXCEEDED);
            }

            // ② 时间：沙箱返回 ms，题目 timeLimit 也是 ms。
            // 宽限由子类给：Java 需要盖住 JVM 启动 + 容器 exec 的开销，其它语言不需要。
            Long timeLimit = judgeConfig.getTimeLimit();
            if (timeLimit != null) {
                long allowance = timeAllowanceMs();
                if (time > timeLimit + allowance) {
                    return fail(judgeInfoResponse, JudgeInfoMessageEnum.TIME_LIMIT_EXCEEDED);
                }
            }
        } else {
            // 每题都打 WARN 会把日志刷满；配置缺失属于数据问题，排查时开 DEBUG 即可
            log.debug("题目判题配置缺失，跳过资源限制判定: questionId={}",
                    judgeContext.getQuestion() == null ? null : judgeContext.getQuestion().getId());
        }

        List<JudgeCase> judgeCaseList = judgeContext.getJudgeCaseList() == null
                ? Collections.emptyList() : judgeContext.getJudgeCaseList();
        List<String> outputList = judgeContext.getOutputList() == null
                ? Collections.emptyList() : judgeContext.getOutputList();

        // ③ 没有测试用例的题目不能放行：否则「用例数为 0」和「输出数为 0」相等，
        //    任何代码都会被判成 Accepted —— 这是一个比判错更糟的结论
        if (judgeCaseList.isEmpty()) {
            log.warn("题目未配置测试用例，无法判题: questionId={}",
                    judgeContext.getQuestion() == null ? null : judgeContext.getQuestion().getId());
            return fail(judgeInfoResponse, JudgeInfoMessageEnum.SYSTEM_ERROR);
        }

        // ④ 输出条数：少一条说明有用例没跑完
        if (outputList.size() != judgeCaseList.size()) {
            return fail(judgeInfoResponse, JudgeInfoMessageEnum.WRONG_ANSWER);
        }

        // ⑤ 逐项比对。用 Objects.equals 而不是 expected.equals(actual)：
        //    预期输出是用户/管理员填的 JSON，可能为 null，直接调 equals 会 NPE
        for (int i = 0; i < judgeCaseList.size(); i++) {
            JudgeCase judgeCase = judgeCaseList.get(i);
            if (!Objects.equals(judgeCase.getOutput(), outputList.get(i))) {
                return fail(judgeInfoResponse, JudgeInfoMessageEnum.WRONG_ANSWER);
            }
        }

        judgeInfoResponse.setMessage(JudgeInfoMessageEnum.ACCEPTED.getValue());
        return judgeInfoResponse;
    }

    /**
     * 这门语言允许比题目时间限制多花多少毫秒。
     *
     * <p>这个值只应该覆盖「程序还没开始跑算法」的固定开销（JVM 启动、容器 exec），
     * 不应该用来给慢算法开后门。</p>
     */
    protected abstract long timeAllowanceMs();

    private JudgeInfo fail(JudgeInfo judgeInfoResponse, JudgeInfoMessageEnum messageEnum) {
        judgeInfoResponse.setMessage(messageEnum.getValue());
        return judgeInfoResponse;
    }

    /**
     * 解析题目的判题配置。解析失败只记日志并返回 null（按「未配置限制」处理），
     * 不能因为一条脏数据让整次判题抛异常 —— 那会把提交卡在「判题中」。
     */
    private JudgeConfig parseJudgeConfig(Question question) {
        if (question == null || StringUtils.isBlank(question.getJudgeConfig())) {
            return null;
        }
        try {
            return JSONUtil.toBean(question.getJudgeConfig(), JudgeConfig.class);
        } catch (Exception e) {
            log.warn("判题配置解析失败，按未配置限制处理: questionId={}, judgeConfig={}",
                    question.getId(), question.getJudgeConfig(), e);
            return null;
        }
    }
}
