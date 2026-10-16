package com.hax.haxojbackend.controller.inner;

import com.hax.haxojbackend.judge.JudgeService;
import com.hax.haxojbackend.model.entity.QuestionSubmit;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 判题服务「内部接口」——只给 question-service 通过 Feign 调用，不对外暴露。
 *
 * <p>对应 service-client 里的 {@code JudgeFeignClient}。</p>
 *
 */
@RestController
@RequestMapping("/judge/inner")
public class JudgeInnerController {

    @Resource
    private JudgeService judgeService;

    /**
     * 执行判题
     * 对应 JudgeFeignClient#doJudge
     */
    @PostMapping("/do")
    public QuestionSubmit doJudge(@RequestParam("questionSubmitId") long questionSubmitId) {
        return judgeService.doJudge(questionSubmitId);
    }
}
