package dev.garden.demo.supervisor;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 统一认证过滤器：所有请求在进入 Controller 之前完成身份解析。
 *
 * <p>这就是“传统互联网项目”的常规做法——认证只在一个地方触发，Controller 不再各自调用：
 *
 * <ul>
 *   <li>过滤器从 {@code Authorization} 头取 Bearer 令牌，交给
 *       {@link AccessPolicy#authenticate(String)} 校验；
 *   <li>校验通过就把 {@link AccessPolicy.Identity} 写入 Reactor Context，
 *       业务层用 {@link RequestIdentity#require()} 读取；Controller 的接口签名里
 *       不会出现身份参数，鉴权对接口完全无感知；
 *   <li>校验失败直接写 401 JSON，请求不会进入业务层；
 *   <li>静态演示页等公开资源走放行名单；新增的接口路径默认受保护，
 *       不依赖开发者“记得写一行认证”。
 * </ul>
 *
 * <p>对比原先在每个 Controller 方法里调用 {@code access.authenticate(auth)}：认证规则本来就只有
 * {@link AccessPolicy} 一份，这次变化的只是<b>触发位置</b>——从 6 个方法收敛到 1 个过滤器。
 * 以后要换令牌格式、调整白名单、统一错误响应，都只改本类。
 *
 * <p>Spring MVC 里的等价物是 {@code Filter}/{@code HandlerInterceptor}，思路一致。
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class SupervisorAuthFilter implements WebFilter {
    /** Reactor Context 的键：业务层用 {@link RequestIdentity#require()} 读取。 */
    public static final String IDENTITY = "supervisor.identity";

    /** 公开资源：静态演示页与其图标不需要令牌。只放行明确列出的路径，其它一律校验。 */
    private static final Set<String> PUBLIC_PATHS = Set.of("/", "/index.html", "/favicon.ico");

    /** 与 SupervisorErrorHandler 的 401 响应保持同样的 JSON 形状，避免过滤器路径返回空 body。 */
    private static final byte[] UNAUTHORIZED_BODY =
            "{\"code\":\"UNAUTHORIZED\",\"message\":\"请提供有效身份令牌。\"}"
                    .getBytes(StandardCharsets.UTF_8);

    private final AccessPolicy access;

    public SupervisorAuthFilter(AccessPolicy access) {
        this.access = access;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        // 1) 公开资源直接放行：白名单是“穷举式”的，没列出来的路径都会被校验。
        if (PUBLIC_PATHS.contains(path)) return chain.filter(exchange);
        // 2) 认证：失败时统一写 401，不把异常继续传给业务层。
        AccessPolicy.Identity identity;
        try {
            identity = access.authenticate(
                    exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
        } catch (ResponseStatusException unauthorized) {
            return writeUnauthorized(exchange);
        }
        // 3) 身份写进 Reactor Context，而不是方法参数：Controller 签名只保留业务参数。
        //    contextWrite 作用于订阅链路，后续 handler 内的 deferContextual 能读到。
        return chain.filter(exchange).contextWrite(context -> context.put(IDENTITY, identity));
    }

    /** 401 用固定 JSON 写出，保证与 Controller 抛异常时的错误格式一致。 */
    private Mono<Void> writeUnauthorized(ServerWebExchange exchange) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(UNAUTHORIZED_BODY)));
    }
}
