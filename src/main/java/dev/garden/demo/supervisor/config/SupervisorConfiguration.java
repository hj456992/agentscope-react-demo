package dev.garden.demo.supervisor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import dev.garden.demo.supervisor.ModelHttpTransport;
import dev.garden.demo.supervisor.OfflineScenarioModel;
import dev.garden.demo.supervisor.RunControl;
import dev.garden.demo.supervisor.Supervisor;
import dev.garden.demo.supervisor.TravelTools;
import dev.garden.demo.supervisor.pojo.dto.LimitsDTO;
import dev.garden.demo.supervisor.pojo.dto.RequestDTO;
import dev.garden.demo.supervisor.pojo.dto.ResultDTO;
import dev.garden.demo.supervisor.repo.ConversationRepository;
import dev.garden.demo.supervisor.repo.Database;
import dev.garden.demo.supervisor.repo.JsonCodec;
import dev.garden.demo.supervisor.repo.SideEffectRepository;
import dev.garden.demo.supervisor.repo.TaskRepository;
import dev.garden.demo.supervisor.service.AccessPolicy;
import dev.garden.demo.supervisor.service.ConversationService;
import dev.garden.demo.supervisor.service.SideEffectLedger;
import dev.garden.demo.supervisor.service.SideEffectReconciler;
import dev.garden.demo.supervisor.service.TaskStoreService;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import reactor.core.publisher.Mono;

import javax.sql.DataSource;

import java.time.Clock;
import java.util.function.BiFunction;
import java.util.function.Function;

/** 容器管理共享依赖；每轮的上下文、模型和 Agent 仍由工厂新建。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SupervisorSettings.class)
@EnableTransactionManagement(proxyTargetClass = true)
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
    public LimitsDTO supervisorLimits(SupervisorSettings settings) {
        return new LimitsDTO(
                settings.timeout(), settings.maxModelCalls(), settings.maxOutputTokens());
    }

    @Bean(destroyMethod = "close")
    public ModelHttpTransport modelHttpTransport() {
        return new ModelHttpTransport();
    }

    @Bean
    public TravelTools.DataSource travelDataSource() {
        return TravelTools.demoData();
    }

    @Bean
    public Function<String, Model> supervisorModels(
            SupervisorSettings settings, Environment environment, ModelHttpTransport transport) {
        if (settings.mode().equals("offline")) {
            return OfflineScenarioModel::new;
        }
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
    public AccessPolicy accessPolicy(SupervisorSettings settings) {
        return new AccessPolicy(settings.authTokens());
    }

    @Bean(destroyMethod = "close")
    public Database supervisorDatabase(SupervisorSettings settings) {
        return Database.open(settings.databaseUrl());
    }

    @Bean
    public DataSource supervisorDataSource(Database database) {
        return database.dataSource();
    }

    @Bean
    public DataSourceTransactionManager supervisorTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    @Bean
    public JsonCodec supervisorJsonCodec(ObjectMapper mapper) {
        return new JsonCodec(mapper);
    }

    @Bean
    public TaskRepository taskRepository(Database database, JsonCodec codec) {
        return new TaskRepository(database, codec);
    }

    @Bean
    public ConversationRepository conversationRepository(Database database, JsonCodec codec) {
        return new ConversationRepository(database, codec);
    }

    @Bean
    public ConversationService conversationService(
            ConversationRepository repository, SupervisorSettings settings) {
        return new ConversationService(
                repository, settings.conversationRetention(), settings.preferenceRetention());
    }

    @Bean
    public TaskStoreService taskStoreService(
            TaskRepository repository,
            ConversationService conversations,
            SupervisorSettings settings) {
        return new TaskStoreService(repository, conversations, settings);
    }

    /**
     * TaskService 的构造器只依赖一个“请求 → 结果”的函数；这里把 Supervisor 的执行入口
     * 适配成该函数，TaskService 本身由 @Service 扫描创建，不再在配置类里手工 new。
     */
    @Bean
    public BiFunction<RequestDTO, RunControl, Mono<ResultDTO>> supervisorRunner(
            Supervisor supervisor) {
        return supervisor::run;
    }

    @Bean
    public SideEffectRepository sideEffectRepository(Database database) {
        return new SideEffectRepository(database);
    }

    @Bean
    public SideEffectLedger sideEffectLedger(SideEffectRepository repository, Clock clock) {
        return new SideEffectLedger(repository, clock);
    }

    @Bean(destroyMethod = "close")
    public SideEffectReconciler sideEffectReconciler(
            SideEffectLedger ledger, ListableBeanFactory beans) {
        // 当前出行工具均为只读；只有明确注册的写入适配器才参与独立状态核对。
        return new SideEffectReconciler(
                ledger, beans.getBeansOfType(SideEffectLedger.Downstream.class));
    }

    @Bean
    public DisposableBean modelTransportCleanup() {
        // 只在容器退出时释放全局传输，绝不能在每个 HTTP 请求结束时关闭。
        return HttpTransportFactory::shutdown;
    }

    private String required(Environment environment, String name) {
        String value = environment.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("真实模型模式缺少配置：" + name);
        }
        return value;
    }
}
