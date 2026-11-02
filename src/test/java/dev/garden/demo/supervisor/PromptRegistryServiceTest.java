package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.garden.demo.supervisor.pojo.param.PromptParam;
import dev.garden.demo.supervisor.pojo.param.PromptReleaseParam;
import dev.garden.demo.supervisor.repo.Database;
import dev.garden.demo.supervisor.repo.GovernanceRepository;
import dev.garden.demo.supervisor.service.PromptRegistryService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Clock;

/** 提示词注册表：种子、版本登记、发布绑定和正文不外泄。 */
class PromptRegistryServiceTest {
    @TempDir Path directory;
    private Database database;
    private PromptRegistryService prompts;

    @BeforeEach
    void setUp() {
        database = TestServices.database("jdbc:h2:file:" + directory.resolve("prompts"));
        prompts = new PromptRegistryService(new GovernanceRepository(database), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void seedRegistersFourRolesAndPublishesVersionOne() {
        assertEquals(4, prompts.list().size());
        var active = prompts.active("weather_agent");
        assertEquals("prompt.travel.weather_agent", active.promptId());
        assertEquals("1", active.version());
        assertEquals("registry", active.source());
        assertFalse(active.content().isBlank());
        assertEquals(4, prompts.releases().size());
    }

    @Test
    void newVersionCanBeReleasedAndResolvedByRole() {
        var created =
                prompts.create(
                        new PromptParam(
                                "prompt.travel.weather_agent",
                                "2",
                                "weather_agent",
                                "travel-platform",
                                "天气助手版本二。",
                                "eval-20261102",
                                "PUBLISHED",
                                "platform-admin"));
        assertEquals("2", created.version());
        assertEquals(64, created.hash().length());

        prompts.release(
                new PromptReleaseParam(
                        "weather_agent",
                        "prompt.travel.weather_agent",
                        "2",
                        "platform-admin"));
        var active = prompts.active("weather_agent");
        assertEquals("2", active.version());
        assertEquals(created.hash(), active.hash());
        assertEquals("eval-20261102", active.evalRecord());
        assertTrue(prompts.list().stream().anyMatch(row -> row.version().equals("2")));
    }

    @Test
    void responseNeverContainsPromptContent() {
        assertThrows(
                NoSuchMethodException.class,
                () -> dev.garden.demo.supervisor.pojo.res.PromptRes.class.getMethod("content"));
    }

    @Test
    void publishedVersionRequiresEvaluationRecordAndRoleMustMatch() {
        ResponseStatusException missingEval =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                prompts.create(
                                        new PromptParam(
                                                "prompt.travel.weather_agent",
                                                "3",
                                                "weather_agent",
                                                "travel-platform",
                                                "正文",
                                                "",
                                                "PUBLISHED",
                                                "platform-admin")));
        assertEquals(HttpStatus.BAD_REQUEST, missingEval.getStatusCode());

        ResponseStatusException wrongRole =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                prompts.release(
                                        new PromptReleaseParam(
                                                "train_agent",
                                                "prompt.travel.weather_agent",
                                                "1",
                                                "platform-admin")));
        assertEquals(HttpStatus.BAD_REQUEST, wrongRole.getStatusCode());

        ResponseStatusException unknown =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                prompts.release(
                                        new PromptReleaseParam(
                                                "weather_agent",
                                                "prompt.travel.weather_agent",
                                                "9",
                                                "platform-admin")));
        assertEquals(HttpStatus.NOT_FOUND, unknown.getStatusCode());
    }
}
