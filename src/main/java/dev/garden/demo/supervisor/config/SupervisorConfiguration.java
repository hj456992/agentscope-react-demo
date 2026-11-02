package dev.garden.demo.supervisor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;

import dev.garden.demo.supervisor.ModelHttpTransport;
import dev.garden.demo.supervisor.OfflineScenarioModel;
import dev.garden.demo.supervisor.RunControl;
import dev.garden.demo.supervisor.Supervisor;
import dev.garden.demo.supervisor.SupervisorPolicy;
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
import dev.garden.demo.supervisor.support.LiveModelFactory;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransportFactory;

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
@EnableConfigurationProperties({SupervisorSettings.class, SupervisorPolicySettings.class})
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

    @Bean
    public SupervisorPolicy supervisorPolicy(SupervisorPolicySettings settings) {
        return settings.toPolicy();
    }

    @Bean(destroyMethod = "close")
    public ModelHttpTransport modelHttpTransport() {
        return new ModelHttpTransport();
    }

    @Bean
    public TravelTools.DataSource travelDataSource(SupervisorSettings settings) {
        if (settings.downstreamUrl() == null || settings.downstreamUrl().isBlank()) {
            return TravelTools.demoData();
        }
        org.slf4j.LoggerFactory.getLogger(getClass())
                .info("只读工具使用本地独立下游：{}", settings.downstreamUrl());
        return new dev.garden.demo.supervisor.support.HttpTravelDataSource(
                settings.downstreamUrl(), settings.downstreamTimeout(), settings.toolDelayMs());
    }

    @Bean
    public Function<String, Model> supervisorModels(
            SupervisorSettings settings, Environment environment, ModelHttpTransport transport) {
        if (settings.mode().equals("offline")) {
            org.slf4j.LoggerFactory.getLogger(getClass()).info("Supervisor 模式：offline（离线脚本）");
            return OfflineScenarioModel::new;
        }
        Function<String, Model> models = LiveModelFactory.create(environment::getProperty, transport);
        String tools =
                settings.downstreamUrl() == null || settings.downstreamUrl().isBlank()
                        ? "天气和车票使用进程内示例数据"
                        : "天气和车票使用本地独立下游：" + settings.downstreamUrl();
        org.slf4j.LoggerFactory.getLogger(getClass())
                .info("Supervisor 模式：live（真实模型；{}）", tools);
        return models;
    }

    @Bean
    public AccessPolicy accessPolicy(SupervisorSettings settings) {
        return new AccessPolicy(settings.authTokens());
    }

    @Bean(destroyMethod = "close")
    public Database supervisorDatabase(SupervisorSettings settings) {
        return Database.open(
                settings.databaseUrl(),
                settings.databaseUsername(),
                settings.databasePassword(),
                settings.databasePoolSize());
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
            ConversationRepository repository,
            SupervisorSettings settings,
            SupervisorPolicy policy) {
        return new ConversationService(
                repository,
                settings.conversationRetention(),
                settings.preferenceRetention(),
                policy.topic());
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
    public dev.garden.demo.supervisor.repo.GovernanceRepository governanceRepository(
            Database database) {
        return new dev.garden.demo.supervisor.repo.GovernanceRepository(database);
    }

    @Bean
    public SideEffectRepository sideEffectRepository(Database database) {
        return new SideEffectRepository(database);
    }

    /**
     * 写入工具的下游适配器；Bean 名称必须与工具名一致，状态核对器才能按工具找到适配器。
     *
     * <p>没有配置本地下游时返回显式不可用适配器，审批仍可登记，但执行阶段明确失败，
     * 不静默写入。Spring 不允许注入 null Bean，占位适配器保证容器启动。
     */
    @Bean(name = dev.garden.demo.supervisor.service.ApprovalService.TOOL)
    public SideEffectLedger.Downstream travelWriteDownstream(SupervisorSettings settings) {
        if (settings.downstreamUrl() == null || settings.downstreamUrl().isBlank()) {
            return new dev.garden.demo.supervisor.support.UnavailableSideEffectDownstream();
        }
        return new dev.garden.demo.supervisor.support.HttpSideEffectDownstream(
                settings.downstreamUrl(), settings.downstreamTimeout());
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
}
