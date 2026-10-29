package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import dev.garden.demo.supervisor.config.*;
import dev.garden.demo.supervisor.pojo.dataobject.*;
import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.pojo.param.*;
import dev.garden.demo.supervisor.pojo.req.*;
import dev.garden.demo.supervisor.pojo.res.*;
import dev.garden.demo.supervisor.repo.*;
import dev.garden.demo.supervisor.service.*;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 独立进程：天气阶段已经保存，火车票工具执行时模拟断电。 */
public final class SupervisorCrashProcess {
    static final int RECOVERY_CALL_LIMIT = 32;
    private static volatile TestRepository crashRepository;

    static SupervisorSettings recoverySettings(String url) {
        SupervisorSettings base = SupervisorRuntimeIntegrationTest.settings(url);
        // 恢复需要重建、摘要等额外模型调用；额度耗尽由专门用例验证。
        // 两个进程使用同一上限，恢复不能重置已经消耗的额度。
        return new SupervisorSettings(
                base.mode(),
                base.timeout(),
                RECOVERY_CALL_LIMIT,
                base.maxOutputTokens(),
                base.maxConcurrentRequests(),
                base.maxConcurrentPerUser(),
                base.tasksPerUserMinute(),
                base.modelCallsPerMinute(),
                1_000_000,
                base.maxStoredTasks(),
                base.taskRetention(),
                base.conversationRetention(),
                base.preferenceRetention(),
                base.databaseUrl(),
                base.authTokens());
    }

    static TravelTools.DataSource source(Path calls, boolean crash) {
        return new TravelTools.DataSource() {
            void record(String value) {
                try (var file =
                        FileChannel.open(
                                calls,
                                StandardOpenOption.CREATE,
                                StandardOpenOption.WRITE,
                                StandardOpenOption.APPEND)) {
                    file.write(ByteBuffer.wrap((value + "\n").getBytes(StandardCharsets.UTF_8)));
                    file.force(true);
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }

            public Map<String, Object> weather(String city, String date) {
                record("weather");
                return TravelTools.demoData().weather(city, date);
            }

            public List<Map<String, Object>> trains(String from, String to, String date) {
                record("train");
                if (crash) {
                    // 子会话并发执行：等天气成功检查点提交后再断电，明确本用例的崩溃边界。
                    long deadline =
                            System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
                    while (crashRepository == null
                            || crashRepository.all().stream()
                                    .noneMatch(
                                            task ->
                                                    task.checkpoints.keySet().stream()
                                                            .anyMatch(
                                                                    key ->
                                                                            key.startsWith(
                                                                                    "read:query_weather:")))) {
                        if (System.nanoTime() >= deadline) {
                            throw new AssertionError("天气成功检查点尚未提交");
                        }
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(error);
                        }
                    }
                    Runtime.getRuntime().halt(24);
                }
                return TravelTools.demoData().trains(from, to, date);
            }
        };
    }

    public static void main(String[] args) throws Exception {
        String url = args[0];
        Supervisor supervisor =
                new Supervisor(
                        OfflineScenarioModel::new,
                        SupervisorRuntimeIntegrationTest.CLOCK,
                        LimitsDTO.defaults(),
                        source(Path.of(args[1]), true));
        try (var repository = new TestRepository(url);
                var tasks =
                        TestServices.taskService(
                                repository, recoverySettings(url), supervisor::run)) {
            crashRepository = repository;
            tasks.submit(
                            IdentityDTO.local(),
                            new RequestDTO(
                                    "crash", "明天从杭州到上海玩一天，规划行程", List.of(), "durable-request"),
                            true)
                    .block();
            new java.util.concurrent.CountDownLatch(1)
                    .await(10, java.util.concurrent.TimeUnit.SECONDS);
            throw new AssertionError("未触发预期崩溃");
        }
    }
}
