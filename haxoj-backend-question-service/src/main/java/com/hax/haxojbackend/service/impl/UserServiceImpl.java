package com.hax.haxojbackend.service.impl;

import static com.hax.haxojbackend.constant.UserConstant.USER_LOGIN_STATE;

import com.hax.haxojbackend.common.ErrorCode;
import com.hax.haxojbackend.exception.BusinessException;
import com.hax.haxojbackend.model.entity.User;
import com.hax.haxojbackend.model.enums.UserRoleEnum;
import com.hax.haxojbackend.model.vo.UserVO;
import com.hax.haxojbackend.service.UserFeignClient;
import com.hax.haxojbackend.service.UserService;
import java.util.Collection;
import java.util.List;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

/**
 * 题目服务里的用户能力实现（精简版）。
 *
 * <p>两条路径：</p>
 * <ol>
 *   <li>登录态 → 读共享 session（Redis 分布式 session，登录时由 user-service 写入）；</li>
 *   <li>用户数据 → 通过 {@link UserFeignClient} 远程调 user-service。</li>
 * </ol>
 */
@Service
public class UserServiceImpl implements UserService {

    @Resource
    private UserFeignClient userFeignClient;

    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 1. 先判断是否已登录（读的是 Redis 里的 session，与 user-service 共享）
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User currentUser = (User) userObj;
        if (currentUser == null || currentUser.getId() == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        // 2. 用户最新信息以 user-service 为准，再远程查一次
        long userId = currentUser.getId();
        User user = userFeignClient.getById(userId);
        if (user == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return user;
    }

    @Override
    public boolean isAdmin(HttpServletRequest request) {
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User user = (User) userObj;
        return isAdmin(user);
    }

    @Override
    public boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }

    @Override
    public User getById(long id) {
        return userFeignClient.getById(id);
    }

    @Override
    public List<User> listByIds(Collection<Long> idList) {
        return userFeignClient.listByIds(idList);
    }
}
