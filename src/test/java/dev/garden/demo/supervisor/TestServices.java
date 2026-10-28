package dev.garden.demo.supervisor;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.garden.demo.supervisor.config.SupervisorSettings;
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
import dev.garden.demo.supervisor.service.TaskService;
import dev.garden.demo.supervisor.service.TaskStoreService;

import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.util.function.BiFunction;

/**
 * 测试装配辅助。
 *
 * <p>生产类各自只保留一个完整构造器；测试在这里打开独立 Database，再组装仓储、会话服务、
 * 副作用账本和 TaskService，不为单测或权限场景给生产类增加构造器重载。
 */
final class TestServices {
    private TestServices() {}

    static Database database(String url) {
        return Database.open(url);
    }

    static TaskRepository taskRepository(Database database) {
        return new TaskRepository(database, new JsonCodec(new ObjectMapper()));
    }

    static ConversationService conversationService(TaskRepository repository) {
        return conversationService(repository, Duration.ofDays(30), Duration.ofDays(90));
    }

    static ConversationService conversationService(
            TaskRepository repository,
            Duration conversationRetention,
            Duration preferenceRetention) {
        return new ConversationService(
                new ConversationRepository(repository.database(), repository.codec()),
                conversationRetention,
                preferenceRetention);
    }

    static SideEffectLedger sideEffectLedger(Database database, Clock clock) {
        return new SideEffectLedger(new SideEffectRepository(database), clock);
    }

    static TaskService taskService(
            TaskRepository repository,
            SupervisorSettings settings,
            BiFunction<RequestDTO, RunControl, Mono<ResultDTO>> runner) {
        return taskService(repository, settings, runner, null);
    }

    static TaskService taskService(
            TaskRepository repository,
            SupervisorSettings settings,
            BiFunction<RequestDTO, RunControl, Mono<ResultDTO>> runner,
            AccessPolicy accessPolicy) {
        return new TaskService(
                new TaskStoreService(
                        repository, conversationService(repository), settings),
                settings,
                runner,
                accessPolicy);
    }

    static TaskService taskService(
            TestRepository repository,
            SupervisorSettings settings,
            BiFunction<RequestDTO, RunControl, Mono<ResultDTO>> runner) {
        return taskService(repository.repository(), settings, runner);
    }

    static TaskService taskService(
            TestRepository repository,
            SupervisorSettings settings,
            BiFunction<RequestDTO, RunControl, Mono<ResultDTO>> runner,
            AccessPolicy accessPolicy) {
        return taskService(repository.repository(), settings, runner, accessPolicy);
    }
}
