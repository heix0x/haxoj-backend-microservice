package com.hax.haxojbackend.service;

import com.hax.haxojbackend.model.entity.Question;
import com.hax.haxojbackend.model.entity.QuestionSubmit;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 题目服务的远程调用契约（Feign 客户端），主要供 judge-service 判题时使用。
 *
 * <p>注意最后一个 {@link #updateQuestionAcceptedNum(long)}：
 * 原单体代码里判题通过后是用 MyBatis-Plus 的 {@code UpdateWrapper} 做
 * {@code acceptedNum = acceptedNum + 1} 的原子更新。{@code UpdateWrapper} 是本地库对象，
 * Feign 无法把它序列化成 HTTP 请求，所以必须换成「具名方法」——由数据归属方
 * （question-service）自己去做原子更新，调用方只传 questionId。</p>
 */
@FeignClient(name = "haxoj-backend-question-service", path = "/api/inner/question")
public interface QuestionFeignClient {

    /**
     * 根据 id 获取题目
     *
     * @param questionId 题目 id
     * @return 题目
     */
    @GetMapping("/get/id")
    Question getQuestionById(@RequestParam("questionId") long questionId);

    /**
     * 根据 id 获取题目提交记录
     *
     * @param questionSubmitId 题目提交 id
     * @return 题目提交记录
     */
    @GetMapping("/question_submit/get/id")
    QuestionSubmit getQuestionSubmitById(@RequestParam("questionSubmitId") long questionSubmitId);

    /**
     * 更新题目提交记录（判题状态、判题信息）
     *
     * @param questionSubmit 只带需要更新的字段
     * @return 是否成功
     */
    @PostMapping("/question_submit/update")
    boolean updateQuestionSubmitById(@RequestBody QuestionSubmit questionSubmit);

    /**
     * 题目通过数 +1（原子更新，避免并发丢更新）
     *
     * @param questionId 题目 id
     * @return 是否成功
     */
    @PostMapping("/question_submit/update/accepted_num")
    boolean updateQuestionAcceptedNum(@RequestParam("questionId") long questionId);
}
