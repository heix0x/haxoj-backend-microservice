package com.hax.haxojbackend.service;

import com.hax.haxojbackend.model.dto.questionsubmit.QuestionSubmitFinishRequest;
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
 * <p>契约遵循「谁的数据谁写」：判题服务需要改提交状态、给通过数加一，
 * 都不是自己 update，而是调这里的具名方法，由 question-service 执行。
 * 这样 {@code UpdateWrapper} 这类本地库对象不会出现在 HTTP 边界上，
 * 也便于把「状态 + 通过数」放进同一个事务（见 {@link #finishQuestionSubmit}）。</p>
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
     * 原子抢占：把提交状态从「等待中」改为「判题中」。
     *
     * <p>用一条带条件的 UPDATE 完成状态流转，返回 true 表示本次抢占成功。
     * 重复消费时只有一个消费者能抢到，避免同一份提交被判两次。</p>
     *
     * @param id 提交记录 id
     * @return 是否抢占成功
     */
    @PostMapping("/question_submit/claim")
    boolean claimQuestionSubmit(@RequestParam("id") long id);

    /**
     * 落定判题结果：状态从「判题中」改为终态，判题信息一并写入。
     *
     * <p>为什么要把「写状态」和「通过数 +1」合成一个接口？原来是两次 Feign 调用，
     * 中间任何一次失败都会重试，而 {@code acceptedNum + 1} 不是幂等的 ——
     * 重试一次就多加一次。现在由提供方在同一个事务里完成，重复调用不会重复累加。</p>
     *
     * <p>返回值语义（调用方只需要关心「要不要重试」）：</p>
     * <ul>
     *   <li>{@code true}  —— 提交现在已经是终态：本次落定成功，或此前已被落定（幂等命中）；</li>
     *   <li>{@code false} —— 状态既不是「判题中」也不是终态（记录不存在、仍是「待判题」），
     *       说明状态机被别的路径改过，可以重试。</li>
     * </ul>
     *
     * @param request 提交 id + 目标状态（2 成功 / 3 失败）+ 判题信息
     * @return 是否已处于终态
     */
    @PostMapping("/question_submit/finish")
    boolean finishQuestionSubmit(@RequestBody QuestionSubmitFinishRequest request);
}
