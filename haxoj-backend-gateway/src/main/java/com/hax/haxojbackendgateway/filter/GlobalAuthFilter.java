package com.hax.haxojbackendgateway.filter;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import reactor.core.publisher.Mono;

/**
 * 网关的唯一闸门：内部接口拦截 + 统一鉴权入口。
 *
 * <p>它由原来的 {@code InnerPathBlockFilter} 演进而来（2026-10-21 合并）。
 * 原来的职责只有一件事：任何含 inner 段的路径都不许被转发出去。
 * 现在在这件事前面保留了「统一鉴权」的位置，但鉴权<b>默认关闭</b>，
 * 所以当前运行时行为与改造前完全一致。</p>
 *
 * <p>三条规则，按顺序：</p>
 * <ol>
 *   <li><b>inner 路径 → 404</b>：内部接口免鉴权（Feign 调用没有用户 Session），
 *       它的安全完全依赖「外部到不了」。返回 404 而不是 403，
 *       是为了让外部无法通过状态码判断「这里是否存在一个内部端点」。</li>
 *   <li><b>未登录 → 401</b>：仅在 {@code haxoj.gateway.auth.enabled=true} 时生效。</li>
 *   <li><b>其余放行</b>：交给后面的路由过滤器（lb:// → NettyRoutingFilter）。</li>
 * </ol>
 *
 * <p>⚠️ 注意它只是「经过网关的请求」的闸门。绕过 8001 直连 8101 的流量，
 * 这个过滤器一次都不会执行 —— 那一层要靠服务只监听 127.0.0.1 / 防火墙。</p>
 */
@Component
public class GlobalAuthFilter implements GlobalFilter, Ordered {

    /** 新形状：inner 路径收敛后的正式前缀 */
    private static final PathPattern NEW_INNER = PathPatternParser.defaultInstance.parse("/api/inner/**");

    /**
     * 旧形状：兜底。用 {service} 单段变量，不能写 /api/**&#47;inner/** ——
     * PathPattern 的 ** 只能出现在末尾，写在中间会解析失败。
     */
    private static final PathPattern OLD_INNER = PathPatternParser.defaultInstance.parse("/api/{service}/inner/**");

    /**
     * 白名单：登录、注册、接口文档。
     * ⚠️ 打开鉴权开关前，必须把「允许匿名访问的业务接口」补全，否则会被 401 拦掉。
     */
    private static final List<PathPattern> WHITE_LIST = Arrays.stream(new String[]{
            "/api/user/login",
            "/api/user/register",
            "/api/user/logout",
            "/api/user/get/login",   // 前端启动时查「当前登录用户」，未登录也要能拿到 40100
            "/api/user/get/vo",      // 公开的用户主页
            "/doc.html",
            "/webjars/**",
            "/v3/api-docs/**",
            "/swagger-resources/**",
            "/api/v2/api-docs",
            "/favicon.ico"
    }).map(PathPatternParser.defaultInstance::parse).collect(Collectors.toList());

    /**
     * 鉴权总开关：默认关闭。
     * 关闭时行为与改造前一致（只拦 inner），避免「还没接入登录态就把全站拦死」。
     */
    @Value("${haxoj.gateway.auth.enabled:false}")
    private boolean authEnabled;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();

        // ① inner：一律 404，且不带响应体 —— 与「没有这条路由」的 404 完全一样
        if (NEW_INNER.matches(path) || OLD_INNER.matches(path)) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }

        // ② 统一鉴权（默认关闭）
        if (authEnabled && !isWhiteListed(path) && !isLoggedIn(exchange)) {
            return rejectJson(exchange, HttpStatus.UNAUTHORIZED, "未登录");
        }

        // ③ 放行：交给后面的路由过滤器（lb:// → 选实例 → 发 HTTP）
        return chain.filter(exchange);
    }

    private boolean isWhiteListed(PathContainer path) {
        return WHITE_LIST.stream().anyMatch(pattern -> pattern.matches(path));
    }

    /**
     * TODO 第 2 步：在这里读登录态（Redis 共享 Session / JWT）。
     *
     * <p>现在固定返回 false，是刻意的「默认拒绝」：
     * 谁把开关打开却忘了实现，请求会被 401，而不是被放行。
     * 接入时注意：网关是 WebFlux，拿不到 {@code HttpServletRequest}，
     * 服务里那套 {@code getLoginUser(request)} 不能直接复用。</p>
     */
    private boolean isLoggedIn(ServerWebExchange exchange) {
        return false;
    }

    /**
     * 写 JSON 响应并短路。
     * {@code writeWith} 本身就会结束响应，不需要再调 {@code setComplete()}。
     */
    private Mono<Void> rejectJson(ServerWebExchange exchange, HttpStatus status, String message) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(new MediaType("application", "json", StandardCharsets.UTF_8));
        String body = "{\"code\":" + status.value() + ",\"message\":\"" + message + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        // 必须早于 NettyRoutingFilter（order = Integer.MAX_VALUE），否则请求已经发出去了
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
