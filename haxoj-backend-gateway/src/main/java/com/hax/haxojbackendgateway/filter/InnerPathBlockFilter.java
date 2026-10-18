package com.hax.haxojbackendgateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

/**
 * 内部接口（inner）拦截面。
 *
 * <p>inner 接口是服务之间通过 Feign 调用的端点，它们<b>必须免鉴权</b>
 * （服务间调用带不了用户的 Session），所以其安全完全依赖「外部到不了」。
 * 网关是唯一对外入口，所以必须保证任何含 inner 段的路径都不会被转发出去。</p>
 *
 * <p>它同时挡住两种形状：
 * <ul>
 *   <li>{@code /api/inner/**} —— inner 路径收敛后的正式前缀；</li>
 *   <li>{@code /api/{service}/inner/**} —— 旧形状，作为兜底：
 *       防止以后有人把路由写成 {@code Path=/api/**} 一把梭，又把 inner 顺手暴露出去。</li>
 * </ul>
 * </p>
 */
@Component
public class InnerPathBlockFilter implements GlobalFilter, Ordered {

    /** 新形状：inner 路径收敛后的正式前缀 */
    private static final PathPattern NEW_INNER = PathPatternParser.defaultInstance.parse("/api/inner/**");

    /**
     * 旧形状：兜底。
     * 这里用 {service} 单段变量而<b>不能</b>写 /api/**&#47;inner/** ——
     * PathPattern 的 ** 只能出现在末尾，写在中间会解析失败。
     */
    private static final PathPattern OLD_INNER = PathPatternParser.defaultInstance.parse("/api/{service}/inner/**");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        if (NEW_INNER.matches(path) || OLD_INNER.matches(path)) {
            // 只 setStatusCode 是不够的：请求会继续转发到下游，下游照常执行，
            // 响应状态还会被下游覆盖。必须 setComplete() 短路，才算真的拦住。
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // 必须早于 NettyRoutingFilter（order = Integer.MAX_VALUE），否则请求已经发出去了
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
