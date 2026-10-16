package com.hax.haxojbackend.judge;


import com.hax.haxojbackend.model.entity.QuestionSubmit;

/**
 * 判题服务（judge-service 本地领域接口）
 */
public interface JudgeService {

    /**
     * 判题
     * @param questionSubmitId
     * @return
     */
    QuestionSubmit doJudge(long questionSubmitId);
}
