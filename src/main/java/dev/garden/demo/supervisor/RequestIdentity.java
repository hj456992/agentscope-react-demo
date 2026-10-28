package dev.garden.demo.supervisor;

import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * 从 Reactor Context 读取当前请求已认证的身份。
 *
 * <p>Controller 的方法签名只保留业务参数，鉴权信息不出现；需要身份时在方法体里调用
 * {@link #require()}，它返回一个 Mono，和任务链自然拼接。
 *
 * <p>为什么用 Reactor Context 而不是 ThreadLocal：WebFlux 会在
 * {@code subscribeOn}/{@code publishOn} 处切换线程，ThreadLocal 在切换后就丢了；
 * Reactor Context 随订阅链传播，是响应式栈里唯一可靠的“请求作用域”。
 *
 * <p>取不到身份直接报错（拒绝），不会退化成某个默认用户——安全默认值必须是失败关闭。
 */
final class RequestIdentity {
    private RequestIdentity() {}

    static Mono<AccessPolicy.Identity> require() {
        return Mono.deferContextual(
                context -> {
                    Optional<AccessPolicy.Identity> identity =
                            context.getOrEmpty(SupervisorAuthFilter.IDENTITY);
                    return identity.map(Mono::just)
                            .orElseGet(
                                    () ->
                                            Mono.error(
                                                    new IllegalStateException(
                                                            "请求上下文缺少已认证身份")));
                });
    }
}
