package dev.garden.demo.supervisor.filter;

import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;
import dev.garden.demo.supervisor.pojo.dto.RequestContextDTO;
import dev.garden.demo.supervisor.pojo.res.ErrorRes;
import dev.garden.demo.supervisor.service.AccessPolicy;
import dev.garden.demo.supervisor.support.Contracts;

import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * 所有接口共用的鉴权与公共请求头处理都在进入 Controller 前完成。
 *
 * <p>身份与 Prefer: respond-async 写入 Reactor context，Controller 方法不再接收请求头参数。
 */
@Component
public class SupervisorRequestFilter implements WebFilter, Ordered {
    private static final String PREFIX = "/api/supervisor/";
    private final AccessPolicy access;

    public SupervisorRequestFilter(AccessPolicy access) {
        this.access = access;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith(PREFIX)) {
            return chain.filter(exchange);
        }
        IdentityDTO identity;
        try {
            identity =
                    access.authenticate(
                            exchange.getRequest().getHeaders().getFirst("Authorization"));
        } catch (ResponseStatusException error) {
            return unauthorized(exchange.getResponse());
        }
        boolean async =
                "respond-async"
                        .equalsIgnoreCase(exchange.getRequest().getHeaders().getFirst("Prefer"));
        RequestContextDTO context = new RequestContextDTO(identity, async);
        return chain.filter(exchange)
                .contextWrite(view -> view.put(RequestContextDTO.class, context));
    }

    private Mono<Void> unauthorized(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body =
                Contracts.json(new ErrorRes("UNAUTHORIZED", "请提供有效身份令牌。"))
                        .getBytes(StandardCharsets.UTF_8);
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
