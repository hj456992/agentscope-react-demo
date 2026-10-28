package dev.garden.demo.supervisor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;
import org.springframework.web.reactive.result.method.annotation.ArgumentResolverConfigurer;

/**
 * 注册 POST 命令对象的参数解析器。
 *
 * <p>控制器接口只声明业务参数；请求头、路径变量与 JSON body 的合并工作放在解析器里，
 * 与默认 WebFlux 配置共存，不替换内置解析器。
 */
@Configuration(proxyBeanMethods = false)
class SupervisorWebConfiguration implements WebFluxConfigurer {
    /**
     * 用 ObjectProvider 延迟取编解码器：ServerCodecConfigurer 由 WebFlux 配置自身提供，
     * 构造本 Bean 时立即注入会形成“配置器 ↔ 配置器集合”的循环依赖。
     */
    private final ObjectProvider<ServerCodecConfigurer> codecs;

    SupervisorWebConfiguration(ObjectProvider<ServerCodecConfigurer> codecs) {
        this.codecs = codecs;
    }

    @Override
    public void configureArgumentResolvers(ArgumentResolverConfigurer configurer) {
        CommandBody body = new CommandBody(codecs.getObject());
        // 自定义解析器先于默认解析器；只匹配 ChatCommand，其它参数走 Spring 默认绑定。
        configurer.addCustomResolver(new ChatCommandResolver(body));
    }
}
