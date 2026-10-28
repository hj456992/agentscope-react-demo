package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.pojo.param.*;
import dev.garden.demo.supervisor.pojo.req.*;
import dev.garden.demo.supervisor.pojo.res.*;
import dev.garden.demo.supervisor.pojo.dataobject.*;
import dev.garden.demo.supervisor.service.*;
import dev.garden.demo.supervisor.repo.*;
import dev.garden.demo.supervisor.config.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;

class SupervisorRestartTest {
    @TempDir Path directory;

    @Test
    void abruptProcessExitResumesActualSdkWithoutRepeatingSuccessfulWeather() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("runtime");
        Path calls = directory.resolve("queries.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process child =
                new ProcessBuilder(
                                java,
                                "-Dfile.encoding=UTF-8",
                                "-cp",
                                System.getProperty("java.class.path"),
                                SupervisorCrashProcess.class.getName(),
                                url,
                                calls.toString())
                        .redirectErrorStream(true)
                        .redirectOutput(directory.resolve("child.log").toFile())
                        .start();
        try {
            assertTrue(child.waitFor(12, TimeUnit.SECONDS), "崩溃子进程必须有界退出");
            assertEquals(24, child.exitValue(), Files.readString(directory.resolve("child.log")));
            try (var repository = new TestRepository(url)) {
                var before = repository.all().stream().filter(task -> task.parentRunId == null).findFirst().orElseThrow();
                int callsBefore = before.calls;
                long deadline = before.deadline;
                assertTrue(callsBefore > 0);
                assertEquals("RUNNING", before.state);
                Supervisor supervisor =
                        new Supervisor(
                                OfflineScenarioModel::new,
                                SupervisorRuntimeIntegrationTest.CLOCK,
                                LimitsDTO.defaults(),
                                SupervisorCrashProcess.source(calls, false));
                try (var tasks =
                        TestServices.taskService(
                                repository,
                                SupervisorRuntimeIntegrationTest.settings(url),
                                supervisor::run)) {
                    var result = SupervisorRuntimeIntegrationTest.done(tasks, before.runId);
                    assertEquals("SUCCESS", result.state(), result.result().toString());
                    assertEquals(deadline, result.deadline());
                    assertTrue(result.modelCalls() > callsBefore);
                    assertTrue(result.modelCalls() <= 16);
                    var events = Files.readAllLines(calls);
                    assertEquals(1, events.stream().filter("weather"::equals).count());
                    assertEquals(2, events.stream().filter("train"::equals).count());
                    assertTrue(result.result().answer().contains("G-DEMO"));
                }
            }
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
            }
        }
    }
}
