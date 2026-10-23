package com.hax.haxojbackend.exception;

import com.hax.haxojbackend.common.BaseResponse;
import com.hax.haxojbackend.common.ErrorCode;
import com.hax.haxojbackend.common.ResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理器
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 业务异常：预期内的失败（参数错、未登录、数据不存在），不是故障。
     *
     * <p>所以这里和下面两个 handler 有三点不同：</p>
     * <ol>
     *   <li><b>级别用 warn 不用 error</b>：业务异常每天可能成千上万条，
     *       用 error 会把告警系统刷满，最后只能把告警关掉 —— 真事故也跟着一起被关掉。</li>
     *   <li><b>不打堆栈</b>：这类异常的抛出点固定就那么几处，堆栈没有增量信息，
     *       只会让日志膨胀几倍。定位靠 code + message 就够。</li>
     *   <li><b>原样回传 code 和 message</b>：这才是有价值的信息 —— 用户需要知道
     *       「你参数填错了」而不是「服务出问题了」。</li>
     * </ol>
     */
    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.warn("BusinessException code={}, message={}", e.getCode(), e.getMessage());
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /**
     * 系统异常：日志留全量（含错误码和堆栈），响应只回统一话术。
     *
     * <p>为什么不把 e.getMessage() 回给前端：系统异常的信息往往是内部细节
     * （"调用代码沙箱失败：..."、"Table 'xxx' doesn't exist"），暴露出去等于给攻击者递地图。
     * 用户只需要知道"服务出问题了"，真正的原因留在日志里。</p>
     */
    @ExceptionHandler(SystemException.class)
    public BaseResponse<?> systemExceptionHandler(SystemException e) {
        log.error("SystemException code={}", e.getCode(), e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }

    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }
}
