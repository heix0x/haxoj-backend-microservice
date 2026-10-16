package com.hax.haxojbackend.judge.strategy;


import com.hax.haxojbackend.model.dto.judge.JudgeInfo;

/**
 * 判题策略
 */
public interface JudgeStrategy {

    /**
     * 执行判题
     * @param judgeContext
     * @return
     */
    JudgeInfo doJudge(JudgeContext judgeContext);
}
