package com.hax.haxojbackend.judge;


import com.hax.haxojbackend.judge.strategy.DefaultJudgeStrategy;
import com.hax.haxojbackend.judge.strategy.JavaLanguageJudgeStrategy;
import com.hax.haxojbackend.judge.strategy.JudgeContext;
import com.hax.haxojbackend.judge.strategy.JudgeStrategy;
import com.hax.haxojbackend.model.dto.judge.JudgeInfo;
import com.hax.haxojbackend.model.entity.QuestionSubmit;
import org.springframework.stereotype.Service;

/**
 * 判题管理（简化调用）
 */
@Service
public class JudgeManager {

    /**
     * 执行判题
     *
     * @param judgeContext
     * @return
     */
    JudgeInfo doJudge(JudgeContext judgeContext) {
        QuestionSubmit questionSubmit = judgeContext.getQuestionSubmit();
        String language = questionSubmit.getLanguage();
        JudgeStrategy judgeStrategy = new DefaultJudgeStrategy();
        if ("java".equals(language)) {
            judgeStrategy = new JavaLanguageJudgeStrategy();
        }
        return judgeStrategy.doJudge(judgeContext);
    }

}
