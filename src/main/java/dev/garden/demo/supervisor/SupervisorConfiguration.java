package dev.garden.demo.supervisor;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;
import java.util.function.Function;

/** 容器管理共享依赖；每轮的上下文、模型和 Agent 仍由工厂新建。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupervisorSettings.class)
public class SupervisorConfiguration {
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictHttpJson() {
        // 拒绝把数字或布尔值自动当成用户问题，避免格式错误悄悄进入模型。
        return builder ->
                builder.postConfigurer(
                        mapper ->
                                mapper.coercionConfigFor(LogicalType.Textual)
                                        .setCoercion(
                                                CoercionInputShape.Integer, CoercionAction.Fail)
                                        .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                                        .setCoercion(
                                                CoercionInputShape.Boolean, CoercionAction.Fail));
    }

    @Bean
    public Clock supervisorClock() {
        return Clock.systemUTC();
    }

    @Bean
    public SupervisorContracts.Limits supervisorLimits(SupervisorSettings settings) {
        return new SupervisorContracts.Limits(
                settings.timeout(), settings.maxModelCalls(), settings.maxOutputTokens(),
                settings.inputBytes());
    }

    @Bean(destroyMethod = "close")
    public ModelHttpTransport modelHttpTransport() {
        return new ModelHttpTransport();
    }

    @Bean
    public TravelTools.DataSource travelDataSource() {
        return TravelTools.demoData();
    }

    /**
     * 模型工厂：按角色名返回模型实例，但每次调用都新建对象。
     *
     * <p>offline 模式返回脚本模型 {@link OfflineScenarioModel}，live 模式用兼容 OpenAI 协议的
     * {@code OpenAIChatModel}。stream(false) 表示使用一次性响应而不是 SSE 增量——
     * 主循环会把增量 collectList 成一条消息，两条路径行为一致；需要真正流式输出时再打开。
     */
    @Bean
    public Function<String, Model> supervisorModels(
            SupervisorSettings settings, Environment environment, ModelHttpTransport transport) {
        if (settings.mode().equals("offline")) return OfflineScenarioModel::new;
        String apiKey = required(environment, "DEMO_API_KEY");
        String baseUrl = required(environment, "DEMO_BASE_URL");
        String modelName = required(environment, "DEMO_MODEL");
        return role ->
                OpenAIChatModel.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .modelName(modelName)
                        .stream(false)
                        .httpTransport(transport)
                        .build();
    }

    @Bean
    public Supervisor supervisor(
            Function<String, Model> models,
            Clock clock,
            SupervisorContracts.Limits limits,
            TravelTools.DataSource data) {
        return new Supervisor(models, clock, limits, data);
    }

    @Bean
    public AccessPolicy accessPolicy(SupervisorSettings settings) {
        return new AccessPolicy(settings.authTokens());
    }

    @Bean
    public SupervisorAuthFilter supervisorAuthFilter(AccessPolicy accessPolicy) {
        // 认证统一在 WebFilter 里做：请求进 Controller 之前就完成校验，接口自身不再逐个调用。
        return new SupervisorAuthFilter(accessPolicy);
    }

    @Bean(destroyMethod = "close")
    public TaskRepository taskRepository(
            SupervisorSettings settings, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        return new TaskRepository(settings.databaseUrl(), mapper);
    }

    @Bean
    public ConversationService conversationService(
            TaskRepository repository, SupervisorSettings settings) {
        return new ConversationService(
                repository, settings.conversationRetention(), settings.preferenceRetention());
    }

    @Bean(destroyMethod = "close")
    public TaskService taskService(
            TaskRepository repository,
            ConversationService conversations,
            SupervisorSettings settings,
            Supervisor supervisor,
            AccessPolicy accessPolicy) {
        return new TaskService(repository, conversations, settings, supervisor, accessPolicy);
    }

    @Bean(destroyMethod = "close")
    public SideEffectLedger sideEffectLedger(
            SupervisorSettings settings, AccessPolicy accessPolicy) {
        return new SideEffectLedger(settings.databaseUrl());
    }

    @Bean(destroyMethod = "close")
    public SideEffectReconciler sideEffectReconciler(
            SideEffectLedger ledger, org.springframework.beans.factory.ListableBeanFactory beans) {
        // 当前出行工具均为只读；只有明确注册的写入适配器才参与独立状态核对。
        return new SideEffectReconciler(
                ledger, beans.getBeansOfType(SideEffectLedger.Downstream.class));
    }

    @Bean
    public DisposableBean modelTransportCleanup() {
        // 只在容器退出时释放全局传输，绝不能在每个 HTTP 请求结束时关闭。
        // 连接池与线程是共享资源，提前关闭会让后续请求拿不到连接；
        // 这里注册成 DisposableBean，由 Spring 在关闭阶段统一调用。
        return HttpTransportFactory::shutdown;
    }

    private String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("真实模型模式缺少配置：" + name);
        return value;
    }
}
