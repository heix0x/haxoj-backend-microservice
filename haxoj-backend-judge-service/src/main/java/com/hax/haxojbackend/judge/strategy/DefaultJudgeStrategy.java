package com.hax.haxojbackend.judge.strategy;

/**
 * 默认判题策略（不针对特定语言）。
 *
 * <p>流程全部在 {@link AbstractJudgeStrategy} 里，这里只说明一件事：
 * 无语言特性的程序按题目给的时间限制严格判定，不给宽限。</p>
 */
public class DefaultJudgeStrategy extends AbstractJudgeStrategy {

    @Override
    protected long timeAllowanceMs() {
        return 0L;
    }
}
