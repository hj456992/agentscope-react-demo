package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 独立进程：天气阶段已经保存，火车票工具执行时模拟断电。 */
public final class SupervisorCrashProcess {
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
                if (crash) Runtime.getRuntime().halt(24);
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
                        Limits.defaults(),
                        source(Path.of(args[1]), true));
        try (var repository = new TaskRepository(url, new ObjectMapper());
                var tasks =
                        new TaskService(
                                repository,
                                new ConversationService(repository),
                                SupervisorRuntimeIntegrationTest.settings(url),
                                supervisor::run)) {
            tasks.submit(
                            AccessPolicy.Identity.local(),
                            new Request("crash", "明天从杭州到上海玩一天，规划行程", List.of(), "durable-request"),
                            true)
                    .block();
            new java.util.concurrent.CountDownLatch(1)
                    .await(10, java.util.concurrent.TimeUnit.SECONDS);
            throw new AssertionError("未触发预期崩溃");
        }
    }
}
