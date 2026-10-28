package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.ChatInput;

import org.springframework.core.MethodParameter;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.HandlerMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 把 {@code POST /chat} 的输入组装成一个 {@link SupervisorController.ChatCommand}。
 *
 * <p>两个来源合并进同一个参数对象：JSON body → {@code request}，{@code Prefer} 请求头 → {@code async}。
 * 这样 Controller 只声明一个业务入参，请求头和请求体都留在解析层。
 *
 * <p>{@code Prefer} 的解析规则：逗号分隔的偏好列表，每项可带分号的参数，
 * {@code respond-async} 出现在任意一项即视为异步。
 */
final class ChatCommandResolver implements HandlerMethodArgumentResolver {
    private final CommandBody body;

    ChatCommandResolver(CommandBody body) {
        this.body = body;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == SupervisorController.ChatCommand.class;
    }

    @Override
    public Mono<Object> resolveArgument(
            MethodParameter parameter, BindingContext bindingContext, ServerWebExchange exchange) {
        boolean async = prefersAsync(exchange.getRequest().getHeaders().getFirst("Prefer"));
        return body.read(CommandBody.chatInputParameter(), bindingContext, exchange)
                .map(input -> new SupervisorController.ChatCommand((ChatInput) input, async));
    }

    /** 只读取头、不修改；大小写不敏感，忽略分号后的参数（如 {@code ;wait=30}）。 */
    static boolean prefersAsync(String prefer) {
        if (prefer == null) return false;
        for (String item : prefer.split(","))
            if ("respond-async".equalsIgnoreCase(item.split(";")[0].trim())) return true;
        return false;
    }
}
