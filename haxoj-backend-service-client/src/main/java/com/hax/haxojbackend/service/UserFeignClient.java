package com.hax.haxojbackend.service;

import com.hax.haxojbackend.model.entity.User;
import java.util.Collection;
import java.util.List;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 用户服务的远程调用契约（Feign 客户端）。
 *
 * <p>关键点：这里只声明「别的服务真正会用到的方法」，不是 user-service 里那份完整的 {@code UserService}。
 * 方法上必须写 HTTP 注解（@GetMapping 等），Feign 才能把它们翻译成真实请求。</p>
 *
 * <p>{@code name} 要与 user-service 的 {@code spring.application.name} 一致，
 * {@code path} 与 user-service 的 {@code UserInnerController} 前缀一致。</p>
 */
@FeignClient(name = "haxoj-backend-user-service", path = "/api/user/inner")
public interface UserFeignClient {

    /**
     * 根据 id 获取用户（供其它服务关联查询用户信息）
     *
     * @param userId 用户 id
     * @return 用户
     */
    @GetMapping("/get/id")
    User getById(@RequestParam("userId") long userId);

    /**
     * 根据 id 列表批量获取用户
     *
     * @param idList 用户 id 列表
     * @return 用户列表
     */
    @GetMapping("/get/ids")
    List<User> listByIds(@RequestParam("idList") Collection<Long> idList);
}
