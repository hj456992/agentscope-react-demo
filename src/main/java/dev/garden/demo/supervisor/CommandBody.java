package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.ChatInput;

import org.springframework.core.MethodParameter;
import org.springframework.core.ReactiveAdapterRegistry;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.reactive.BindingContext;
import org.springframework.web.reactive.result.method.annotation.RequestBodyMethodArgumentResolver;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 复用 Spring 自带的 JSON 请求体解析，把 body 读成指定类型。
 *
 * <p>为什么需要它：{@code ChatCommand} 要同时承接请求头和 JSON body，而 Spring 的
 * {@code @RequestBody} 只能绑定 body。与其自己用 ObjectMapper 读流（会丢掉编码协商、
 * 415/413、错误映射），不如直接委托 {@link RequestBodyMethodArgumentResolver}——
 * 它就是 WebFlux 里 {@code @RequestBody} 背后的实现，行为与普通接口完全一致。
 */
final class CommandBody {
    private static final MethodParameter CHAT_INPUT_PARAMETER =
            parameter("chatInput", ChatInput.class);

    private final RequestBodyMethodArgumentResolver processor;

    CommandBody(ServerCodecConfigurer codecs) {
        this.processor =
                new RequestBodyMethodArgumentResolver(
                        codecs.getReaders(), ReactiveAdapterRegistry.getSharedInstance());
    }

    static MethodParameter chatInputParameter() {
        return CHAT_INPUT_PARAMETER;
    }

    Mono<Object> read(
            MethodParameter parameter, BindingContext bindingContext, ServerWebExchange exchange) {
        return processor.resolveArgument(parameter, bindingContext, exchange);
    }

    private static MethodParameter parameter(String name, Class<?> type) {
        try {
            return new MethodParameter(CommandBody.class.getDeclaredMethod(name, type), 0);
        } catch (NoSuchMethodException error) {
            throw new ExceptionInInitializerError(error);
        }
    }

    /** 只用于生成带类型和 {@code @RequestBody} 注解的 MethodParameter，不会被调用。 */
    @SuppressWarnings("unused")
    private void chatInput(@RequestBody ChatInput input) {}
}
