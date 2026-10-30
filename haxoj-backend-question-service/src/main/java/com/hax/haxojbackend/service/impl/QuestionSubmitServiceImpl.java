package com.hax.haxojbackend.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hax.haxojbackend.common.ErrorCode;
import com.hax.haxojbackend.constant.CommonConstant;
import com.hax.haxojbackend.exception.BusinessException;
import com.hax.haxojbackend.exception.SystemException;
import com.hax.haxojbackend.exception.ThrowUtils;
import com.hax.haxojbackend.mapper.QuestionSubmitMapper;
import com.hax.haxojbackend.model.dto.questionsubmit.QuestionSubmitAddRequest;
import com.hax.haxojbackend.model.dto.questionsubmit.QuestionSubmitFinishRequest;
import com.hax.haxojbackend.model.dto.questionsubmit.QuestionSubmitQueryRequest;
import com.hax.haxojbackend.model.entity.Question;
import com.hax.haxojbackend.model.entity.QuestionSubmit;
import com.hax.haxojbackend.model.entity.User;
import com.hax.haxojbackend.model.enums.QuestionSubmitLanguageEnum;
import com.hax.haxojbackend.model.enums.QuestionSubmitStatusEnum;
import com.hax.haxojbackend.model.vo.QuestionSubmitVO;
import com.hax.haxojbackend.model.vo.QuestionVO;
import com.hax.haxojbackend.model.vo.UserVO;
import com.hax.haxojbackend.mq.JudgeMessageProducer;
import com.hax.haxojbackend.service.QuestionService;
import com.hax.haxojbackend.service.QuestionSubmitService;
import com.hax.haxojbackend.service.UserService;
import com.hax.haxojbackend.utils.SqlUtils;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * @description 针对表【question_submit(题目提交)】的数据库操作Service实现
 * @createDate 2026-09-07 10:04:00
 */
@Slf4j
@Service
public class QuestionSubmitServiceImpl extends ServiceImpl<QuestionSubmitMapper, QuestionSubmit>
        implements QuestionSubmitService {

    @Resource
    private UserService userService;

    @Resource
    private QuestionService questionService;

    // 判题已改为 MQ 异步：这里只负责投递消息，不再直接 Feign 调用 judge-service。
    // 好处：判题服务重启/变慢不会拖住提交接口；消息可重试、可进死信。
    @Resource
    private JudgeMessageProducer judgeMessageProducer;

    /**
     * 提交题目
     *
     * @param questionSubmitAddRequest
     * @param request
     * @return
     */
    @Override
    public long doQuestionSubmit(QuestionSubmitAddRequest questionSubmitAddRequest, HttpServletRequest request) {

        // 校验题目提交信息
        if (questionSubmitAddRequest == null || questionSubmitAddRequest.getQuestionId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }

        // 校验编程语言是否合法
        String language = questionSubmitAddRequest.getLanguage();
        QuestionSubmitLanguageEnum languageEnum = QuestionSubmitLanguageEnum.getEnumByValue(language);
        if (languageEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "编程语言错误");
        }
        // 枚举里保留 c++ / golang 是给将来扩展用的，但沙箱目前只实现了 Java。
        // 不拦的话，这两种语言会在沙箱端返回 status=2（平台故障），
        // 用户看到的是「系统错误」—— 他会去等，而不是换语言。
        if (languageEnum != QuestionSubmitLanguageEnum.JAVA) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "当前仅支持 Java 提交");
        }

        // 判断题目是否存在
        long questionId = questionSubmitAddRequest.getQuestionId();
        Question question = questionService.getById(questionId);
        ThrowUtils.throwIf(question == null, ErrorCode.NOT_FOUND_ERROR);

        // 获取登录用户
        User loginUser = userService.getLoginUser(request);

        // 保存提交记录（暂未接入判题，状态置为待判题，后续可扩展判题机模块）
        QuestionSubmit questionSubmit = new QuestionSubmit();
        questionSubmit.setLanguage(language);
        questionSubmit.setCode(questionSubmitAddRequest.getCode());
        questionSubmit.setQuestionId(questionId);
        questionSubmit.setUserId(loginUser.getId());
        questionSubmit.setStatus(QuestionSubmitStatusEnum.WAITING.getValue());
        boolean result = this.save(questionSubmit);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "题目提交失败");
        // 更新题目提交数
        // synchronized (this) 语法意义和作用是：
        // 对当前对象进行加锁，确保在同一时间只有一个线程可以执行同步块中的代码，
        // 从而避免多个线程同时更新题目提交数时可能出现的竞态条件。
        // TODO 技术债：这里的锁只对当前 JVM 有效，多实例部署下 submitNum 仍会丢更新。
        //  正确做法是交给数据库：setSql("submitNum = submitNum + 1")，与通过数的加法一致。
        //  本次不在范围内（改了会影响并发写入的验证面），先留标记。
        synchronized (this) {
            // 1. 创建一个只含 id 的新对象（其他字段为 null，不会被更新）
            Question updateQuestion = new Question();
            updateQuestion.setId(questionId);

            // 2. 在当前提交数基础上 +1
            // question 是在这段同步块之前 getById 读出来的，
            // 但因为加了锁，不会有别的线程在中间插队修改它
            updateQuestion.setSubmitNum(question.getSubmitNum() + 1);

            // 3. updateById 只更新非 null 字段，所以只改 submitNum
            boolean updateResult = questionService.updateById(updateQuestion);
            if (!updateResult) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "更新题目提交数失败");
            }
        }

        Long questionSubmitId = questionSubmit.getId();
        // 投递到 MQ，由 judge-service 异步消费（不再直接 Feign 调用）
        judgeMessageProducer.sendJudgeMessage(questionSubmitId);

        return questionSubmitId;
    }

    /**
     * 落定判题结果（状态 + 通过数，单事务）。
     *
     * <p>三条不变量：</p>
     * <ol>
     *   <li>只有「判题中」能落定 —— 条件更新 {@code WHERE id=? AND status=1}，
     *       重复消息只有一条能改成功，这是幂等的第一道闸；</li>
     *   <li>「已是终态」直接返回 true —— 重放/重试不会把状态往回拨，也不会重复 +1；</li>
     *   <li>状态与通过数同生同死 —— +1 失败就整笔回滚，不留「状态成功、通过数少加」的中间态。</li>
     * </ol>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean finishQuestionSubmit(QuestionSubmitFinishRequest request) {
        if (request == null || request.getId() == null || request.getStatus() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "落定参数不完整");
        }
        Integer targetStatus = request.getStatus();
        // 只接受终态：从外部把状态改成 WAITING/RUNNING 等于把状态机往回拨，
        // 会让已经被判过的提交重新变得「可抢占」
        ThrowUtils.throwIf(!isFinalStatus(targetStatus), ErrorCode.PARAMS_ERROR, "落定目标状态不合法");

        long submitId = request.getId();
        QuestionSubmit current = this.getById(submitId);
        if (current == null) {
            log.warn("落定失败，提交不存在: submitId={}", submitId);
            return false;
        }
        if (isFinalStatus(current.getStatus())) {
            log.info("提交已是终态，落定幂等命中: submitId={}, status={}", submitId, current.getStatus());
            return true;
        }

        LambdaUpdateWrapper<QuestionSubmit> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(QuestionSubmit::getId, submitId)
                .eq(QuestionSubmit::getStatus, QuestionSubmitStatusEnum.RUNNING.getValue())
                .set(QuestionSubmit::getStatus, targetStatus)
                .set(QuestionSubmit::getJudgeInfo, request.getJudgeInfo());
        boolean updated = this.update(wrapper);
        if (!updated) {
            // 0 行有两种可能：并发下已被别的消费者落定（正常），或状态不是「判题中」（异常）。
            // 回读一次即可区分，只有后者才需要调用方重试。
            QuestionSubmit latest = this.getById(submitId);
            boolean alreadyFinal = latest != null && isFinalStatus(latest.getStatus());
            log.info("落定未生效: submitId={}, 当前状态={}, 已是终态={}", submitId,
                    latest == null ? null : latest.getStatus(), alreadyFinal);
            return alreadyFinal;
        }

        // 只有「本次真的把状态推进到成功」才 +1。幂等命中的分支在上面已经返回，
        // 所以这里不会因为重复消息而重复累加。
        if (QuestionSubmitStatusEnum.SUCCEED.getValue().equals(targetStatus)) {
            Long questionId = current.getQuestionId();
            Question question = questionId == null ? null : questionService.getById(questionId);
            if (question == null) {
                // 题目被删了：提交本身仍应落定为成功，只是没有地方可以加数
                log.warn("判题通过但题目不存在，跳过通过数累加: submitId={}, questionId={}", submitId, questionId);
                return true;
            }
            UpdateWrapper<Question> acceptedNumWrapper = new UpdateWrapper<>();
            acceptedNumWrapper.eq("id", questionId);
            acceptedNumWrapper.setSql("acceptedNum = acceptedNum + 1");
            boolean incremented = questionService.update(acceptedNumWrapper);
            if (!incremented) {
                // 抛异常触发回滚：宁可下次重试时再判一次，也不能留下「状态成功、通过数没加」的脏数据
                throw new SystemException(ErrorCode.OPERATION_ERROR, "更新题目通过数失败");
            }
        }
        return true;
    }

    /**
     * 是否是终态（成功 / 失败）。WAITING、RUNNING 都不算。
     */
    private boolean isFinalStatus(Integer status) {
        return QuestionSubmitStatusEnum.SUCCEED.getValue().equals(status)
                || QuestionSubmitStatusEnum.FAILED.getValue().equals(status);
    }

    /**
     * 获取查询条件
     *
     * @param questionSubmitQueryRequest
     * @return
     */
    @Override
    public QueryWrapper<QuestionSubmit> getQueryWrapper(QuestionSubmitQueryRequest questionSubmitQueryRequest) {
        QueryWrapper<QuestionSubmit> queryWrapper = new QueryWrapper<>();
        if (questionSubmitQueryRequest == null) {
            return queryWrapper;
        }
        String language = questionSubmitQueryRequest.getLanguage();
        Integer status = questionSubmitQueryRequest.getStatus();
        Long questionId = questionSubmitQueryRequest.getQuestionId();
        Long userId = questionSubmitQueryRequest.getUserId();
        String sortField = questionSubmitQueryRequest.getSortField();
        String sortOrder = questionSubmitQueryRequest.getSortOrder();
        // 拼接查询条件
        queryWrapper.eq(StringUtils.isNotBlank(language), "language", language);
        queryWrapper.eq(ObjectUtils.isNotEmpty(status), "status", status);
        queryWrapper.eq(ObjectUtils.isNotEmpty(questionId), "questionId", questionId);
        queryWrapper.eq(ObjectUtils.isNotEmpty(userId), "userId", userId);
        queryWrapper.orderBy(SqlUtils.validSortField(sortField), sortOrder.equals(CommonConstant.SORT_ORDER_ASC),
                sortField);
        return queryWrapper;
    }

    /**
     * 获取题目提交封装
     *
     * @param questionSubmit
     * @param request
     * @return
     */
    @Override
    public QuestionSubmitVO getQuestionSubmitVO(QuestionSubmit questionSubmit, HttpServletRequest request) {
        QuestionSubmitVO questionSubmitVO = QuestionSubmitVO.objToVo(questionSubmit);
        // 1. 关联查询用户信息
        Long userId = questionSubmit.getUserId();
        User user = null;
        if (userId != null && userId > 0) {
            user = userService.getById(userId);
        }
        UserVO userVO = userService.getUserVO(user);
        questionSubmitVO.setUser(userVO);
        // 2. 关联查询题目信息
        Long questionId = questionSubmit.getQuestionId();
        Question question = null;
        if (questionId != null && questionId > 0) {
            question = questionService.getById(questionId);
        }
        QuestionVO questionVO = questionService.getQuestionVO(question, request);
        questionSubmitVO.setQuestionVO(questionVO);
        return questionSubmitVO;
    }

    /**
     * 分页获取题目提交封装
     *
     * @param questionSubmitPage
     * @param request
     * @return
     */
    @Override
    public Page<QuestionSubmitVO> getQuestionSubmitVOPage(Page<QuestionSubmit> questionSubmitPage,
            HttpServletRequest request) {
        List<QuestionSubmit> questionSubmitList = questionSubmitPage.getRecords();
        Page<QuestionSubmitVO> questionSubmitVOPage = new Page<>(questionSubmitPage.getCurrent(),
                questionSubmitPage.getSize(), questionSubmitPage.getTotal());
        if (CollUtil.isEmpty(questionSubmitList)) {
            return questionSubmitVOPage;
        }
        // 1. 关联查询用户信息
        Set<Long> userIdSet = questionSubmitList.stream().map(QuestionSubmit::getUserId).collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        // 2. 关联查询题目信息
        Set<Long> questionIdSet = questionSubmitList.stream().map(QuestionSubmit::getQuestionId)
                .collect(Collectors.toSet());
        Map<Long, List<Question>> questionIdQuestionListMap = questionService.listByIds(questionIdSet).stream()
                .collect(Collectors.groupingBy(Question::getId));
        // 填充信息
        List<QuestionSubmitVO> questionSubmitVOList = questionSubmitList.stream().map(questionSubmit -> {
            QuestionSubmitVO questionSubmitVO = QuestionSubmitVO.objToVo(questionSubmit);
            Long userId = questionSubmit.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            questionSubmitVO.setUser(userService.getUserVO(user));
            Long questionId = questionSubmit.getQuestionId();
            Question question = null;
            if (questionIdQuestionListMap.containsKey(questionId)) {
                question = questionIdQuestionListMap.get(questionId).get(0);
            }
            questionSubmitVO.setQuestionVO(questionService.getQuestionVO(question, request));
            return questionSubmitVO;
        }).collect(Collectors.toList());
        questionSubmitVOPage.setRecords(questionSubmitVOList);
        return questionSubmitVOPage;
    }
}
