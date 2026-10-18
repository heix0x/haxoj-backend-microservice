package com.hax.haxojbackend.inner;

import com.hax.haxojbackend.model.entity.User;
import com.hax.haxojbackend.service.UserService;
import java.util.Collection;
import java.util.List;
import javax.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户服务「内部接口」——只给别的微服务通过 Feign 调用，不对外暴露。
 *
 * <p>外部用户接口 {@code /api/user/**}（UserController）返回 BaseResponse、做登录校验；
 * 内部接口 {@code /api/inner/user/**} 则直接返回实体，供服务间使用。
 * 把两者分开，可以避免「服务间调用」和「浏览器访问」共用同一套鉴权/脱敏逻辑。</p>
 *
 * <p>⚠️ 两处刻意安排，都是为了让内部接口到不了外部：</p>
 * <ol>
 *   <li><b>路径</b>排在 {@code /api/inner/} 段下，而不是 {@code /api/user/inner/}：
 *       后者落在网关对外路由 {@code Path=/api/user/**} 的覆盖范围里，会被顺手转发出去。</li>
 *   <li><b>包</b>放在 {@code com.hax.haxojbackend.inner}，而不是 {@code controller.inner}：
 *       knife4j 是按 {@code com.hax.haxojbackend.controller} 包扫描生成文档的，
 *       留在 controller 子包下会被一起扫进网关的 /doc.html 对外公开。</li>
 * </ol>
 */
@RestController
@RequestMapping("/inner/user")
public class UserInnerController {

    @Resource
    private UserService userService;

    /**
     * 根据 id 获取用户
     * 对应 UserFeignClient#getById
     */
    @GetMapping("/get/id")
    public User getById(@RequestParam("userId") long userId) {
        return userService.getById(userId);
    }

    /**
     * 根据 id 列表批量获取用户
     * 对应 UserFeignClient#listByIds
     */
    @GetMapping("/get/ids")
    public List<User> listByIds(@RequestParam("idList") Collection<Long> idList) {
        return userService.listByIds(idList);
    }
}
