package com.hax.haxojbackend.judge.strategy;

/**
 * Java 程序的判题策略。
 *
 * <p>与默认策略的唯一区别是时间宽限：JVM 启动本身就要几十到几百毫秒
 * （容器模式下还要加上 docker exec 的固定开销），这部分开销不该算在用户算法头上。</p>
 *
 * <p>⚠️ 原来这里写的是 {@code (time - 10000) > timeLimit}，等于给所有提交免了 10 秒；
 * 而沙箱自己的执行上限是 5 秒，所以「超时」这个结论<b>永远不可能</b>出现。
 * 宽限必须小于沙箱上限才有意义。</p>
 */
public class JavaLanguageJudgeStrategy extends AbstractJudgeStrategy {

    /**
     * 给 JVM 启动与容器 exec 的固定开销留的余量。
     * 实测 openjdk:8-alpine 冷启动约 100~300ms，这里取 1000ms 留够但不过分。
     */
    private static final long JVM_STARTUP_ALLOWANCE_MS = 1000L;

    @Override
    protected long timeAllowanceMs() {
        return JVM_STARTUP_ALLOWANCE_MS;
    }
}
