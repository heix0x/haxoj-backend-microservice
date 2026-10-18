package com.hax.haxojbackend.judge;

import com.hax.haxojbackend.model.entity.QuestionSubmit;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 判题服务的远程调用契约（Feign 客户端），供 question-service 提交后异步触发判题。
 *
 * <p>判题通常耗时较长，调用方（question-service）用 CompletableFuture 异步调用，
 * 不阻塞用户请求，所以这里只暴露一个「开始判题」的入口。</p>
 */
@FeignClient(name = "haxoj-backend-judge-service", path = "/api/inner/judge")
public interface JudgeFeignClient {

    /**
     * 执行判题
     *
     * @param questionSubmitId 题目提交 id
     * @return 判题后的提交记录
     */
    @PostMapping("/do")
    QuestionSubmit doJudge(@RequestParam("questionSubmitId") long questionSubmitId);
}
