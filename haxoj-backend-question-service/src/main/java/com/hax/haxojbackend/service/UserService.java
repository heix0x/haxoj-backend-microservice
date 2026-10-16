package com.hax.haxojbackend.service;

import com.hax.haxojbackend.model.entity.User;
import com.hax.haxojbackend.model.vo.UserVO;
import java.util.Collection;
import java.util.List;
import javax.servlet.http.HttpServletRequest;

/**
 * 题目服务视角下的「用户能力」——精简版本地接口。
 *
 * <p>question-service 也会用到用户相关能力，但它不是 user-service，不能直接注入
 * user-service 的 {@code UserServiceImpl}（那是另一个进程里的对象）。所以这里留一个
 * **只包含本服务真正需要的方法** 的本地接口：</p>
 *
 * <ul>
 *   <li>{@code getLoginUser / isAdmin}：读分布式 session 的本地逻辑，不走网络；</li>
 *   <li>{@code getById / listByIds}：由 {@code UserServiceImpl} 内部通过 Feign 调 user-service；</li>
 *   <li>{@code getUserVO}：本地脱敏转换。</li>
 * </ul>
 *
 * <p>对比一下：service-client 里的 {@code UserFeignClient} 是「别的服务要用 user-service 的什么」，
 * 这里的接口是「question-service 自己要用用户的什么」——两者都体现了「接口只保留被需要的方法」。</p>
 */
public interface UserService {

    /**
     * 获取当前登录用户（读共享 session + 远程补全最新用户信息）
     *
     * @param request
     * @return
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 是否为管理员
     *
     * @param request
     * @return
     */
    boolean isAdmin(HttpServletRequest request);

    /**
     * 是否为管理员
     *
     * @param user
     * @return
     */
    boolean isAdmin(User user);

    /**
     * 获取脱敏的用户信息
     *
     * @param user
     * @return
     */
    UserVO getUserVO(User user);

    /**
     * 根据 id 获取用户（远程调用 user-service）
     *
     * @param id 用户 id
     * @return
     */
    User getById(long id);

    /**
     * 根据 id 列表批量获取用户（远程调用 user-service）
     *
     * @param idList 用户 id 列表
     * @return
     */
    List<User> listByIds(Collection<Long> idList);
}
