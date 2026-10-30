package com.hax.haxojbackend.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hax.haxojbackend.model.dto.questionsubmit.QuestionSubmitAddRequest;
import com.hax.haxojbackend.model.dto.questionsubmit.QuestionSubmitFinishRequest;
import com.hax.haxojbackend.model.dto.questionsubmit.QuestionSubmitQueryRequest;
import com.hax.haxojbackend.model.entity.QuestionSubmit;
import com.hax.haxojbackend.model.vo.QuestionSubmitVO;
import javax.servlet.http.HttpServletRequest;

/**
 * 题目提交服务（question-service 本地完整领域接口）
 *
 * @description 针对表【question_submit(题目提交)】的数据库操作Service
 * @createDate 2026-09-07 10:04:00
 */
public interface QuestionSubmitService extends IService<QuestionSubmit> {

    /**
     * 提交题目
     *
     * @param questionSubmitAddRequest
     * @param request
     * @return 提交记录 id
     */
    long doQuestionSubmit(QuestionSubmitAddRequest questionSubmitAddRequest, HttpServletRequest request);

    /**
     * 落定判题结果：把提交从「判题中」改为终态，并在通过时给题目通过数 +1。
     *
     * <p>整个方法在一个事务里：要么状态和通过数一起生效，要么都不生效。
     * 重复调用是安全的 —— 状态已经是终态时直接返回 true，不会重复累加。</p>
     *
     * @param request 提交 id + 目标状态（2 成功 / 3 失败）+ 判题信息
     * @return true 表示提交现在已是终态（含幂等命中）；false 表示状态异常，调用方可以重试
     */
    boolean finishQuestionSubmit(QuestionSubmitFinishRequest request);

    /**
     * 获取查询条件
     *
     * @param questionSubmitQueryRequest
     * @return
     */
    QueryWrapper<QuestionSubmit> getQueryWrapper(QuestionSubmitQueryRequest questionSubmitQueryRequest);

    /**
     * 获取题目提交封装
     *
     * @param questionSubmit
     * @param request
     * @return
     */
    QuestionSubmitVO getQuestionSubmitVO(QuestionSubmit questionSubmit, HttpServletRequest request);

    /**
     * 分页获取题目提交封装
     *
     * @param questionSubmitPage
     * @param request
     * @return
     */
    Page<QuestionSubmitVO> getQuestionSubmitVOPage(Page<QuestionSubmit> questionSubmitPage,
            HttpServletRequest request);
}
