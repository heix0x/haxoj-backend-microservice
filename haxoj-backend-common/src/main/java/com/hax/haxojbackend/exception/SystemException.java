package com.hax.haxojbackend.exception;

import com.hax.haxojbackend.common.ErrorCode;

/**
 * 系统异常：表示「现在做不成，但换个时间可能成」。
 *
 * <p>它与 {@link BusinessException} 的区别**不在「谁抛的」，而在「该不该重试」**：</p>
 * <ul>
 *   <li><b>业务异常</b>：重试一万次结果一样（提交不存在、参数不合法、没权限）→ 调用方不该重试</li>
 *   <li><b>系统异常</b>：可能只是网络抖了一下、下游忙了一下（DB 更新失败、Feign 调不通、沙箱挂了）
 *       → 值得重试</li>
 * </ul>
 *
 * <p>这个区别在**有自动重试机制**的地方会直接影响行为：MQ 消费、Feign 重试、定时任务重跑。
 * 例如 {@code JudgeMessageConsumer} 里，业务异常被 ack 丢弃，系统异常进死信队列等待重放。</p>
 *
 * <p>判别方法只有一句：<b>「换个时间再试一次，有可能成功吗？」</b>
 * 可能 → 用这个；不可能 → 用 {@link BusinessException}。</p>
 */
public class SystemException extends RuntimeException {

    /**
     * 错误码。仅用于日志与监控定位，**不直接回传给前端**（系统异常的细节不该暴露给调用方）。
     */
    private final int code;

    public SystemException(int code, String message) {
        super(message);
        this.code = code;
    }

    public SystemException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public SystemException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    /**
     * 保留底层原因（比如 FeignException、SQLException），排查时能顺着 cause 看到根。
     */
    public SystemException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.code = errorCode.getCode();
    }

    public int getCode() {
        return code;
    }
}
