package com.hax.haxojbackend.controller.inner;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.hax.haxojbackend.model.entity.Question;
import com.hax.haxojbackend.model.entity.QuestionSubmit;
import com.hax.haxojbackend.service.QuestionService;
import com.hax.haxojbackend.service.QuestionSubmitService;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 题目服务「内部接口」——只给 judge-service 通过 Feign 调用，不对外暴露。
 *
 * <p>对应 service-client 里的 {@code QuestionFeignClient}，方法一一对应。</p>
 *
 */
@RestController
@RequestMapping("/question/inner")
public class QuestionInnerController {

    @Resource
    private QuestionService questionService;

    @Resource
    private QuestionSubmitService questionSubmitService;

    /**
     * 根据 id 获取题目
     * 对应 QuestionFeignClient#getQuestionById
     */
    @GetMapping("/get/id")
    public Question getQuestionById(@RequestParam("questionId") long questionId) {
        return questionService.getById(questionId);
    }

    /**
     * 根据 id 获取题目提交记录
     * 对应 QuestionFeignClient#getQuestionSubmitById
     */
    @GetMapping("/question_submit/get/id")
    public QuestionSubmit getQuestionSubmitById(@RequestParam("questionSubmitId") long questionSubmitId) {
        return questionSubmitService.getById(questionSubmitId);
    }

    /**
     * 更新题目提交记录（判题状态、判题信息）
     * 对应 QuestionFeignClient#updateQuestionSubmitById
     */
    @PostMapping("/question_submit/update")
    public boolean updateQuestionSubmitById(@RequestBody QuestionSubmit questionSubmit) {
        return questionSubmitService.updateById(questionSubmit);
    }

    /**
     * 题目通过数 +1（原子更新，避免并发丢更新）
     * 对应 QuestionFeignClient#updateQuestionAcceptedNum
     *
     * <p>原单体代码在 judge-service 里直接 new 了一个 UpdateWrapper 去操作 question 表，
     * 但那张表属于 question-service。重构后把「写库」这件事留在数据归属方，
     * 调用方只发一个 questionId，原子更新语句由本服务执行。</p>
     */
    @PostMapping("/question_submit/update/accepted_num")
    public boolean updateQuestionAcceptedNum(@RequestParam("questionId") long questionId) {
        UpdateWrapper<Question> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("id", questionId);
        updateWrapper.setSql("acceptedNum = acceptedNum + 1");
        return questionService.update(updateWrapper);
    }
}
