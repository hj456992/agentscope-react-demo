package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

class ConversationServiceTest {
    private final String url = "jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    private TaskRepository repository =
            new TaskRepository(url, new ObjectMapper().findAndRegisterModules());
    private ConversationService service = new ConversationService(repository);
    private final AccessPolicy.Identity alice =
            new AccessPolicy.Identity("tenant", "alice", Set.of("memory:write"));
    private final AccessPolicy.Identity bob =
            new AccessPolicy.Identity("tenant", "bob", Set.of("memory:write"));

    @AfterEach
    void close() {
        repository.close();
    }

    private ChatInput request(String question) {
        return new ChatInput("session", question, List.of(), UUID.randomUUID().toString());
    }

    private Result answer(String text) {
        return new Result(
                Status.SUCCESS,
                "question",
                "rewritten",
                Intent.TRAIN,
                text,
                List.of(),
                List.of(),
                List.of());
    }

    private String history(ChatInput request) {
        return request.history().toString();
    }

    private void status(int expected, Runnable call) {
        assertEquals(
                expected,
                assertThrows(ResponseStatusException.class, call::run).getStatusCode().value());
    }

    @Test
    void canonicalHistorySurvivesRestartAndCannotBeOverwrittenByClient() {
        ChatInput original = request("只坐二等座");
        service.complete(alice, original, answer("已确认"), "run-1");
        service.complete(alice, request("覆盖原问题"), answer("覆盖"), "run-1");
        repository.close();
        repository = new TaskRepository(url, new ObjectMapper().findAndRegisterModules());
        service = new ConversationService(repository);
        ChatInput enriched =
                service.enrich(
                        alice,
                        new ChatInput("session", "明天出发", List.of(new Turn("改成商务座", "我已确认")), "next"));
        assertEquals("明天出发", enriched.question());
        assertTrue(history(enriched).contains("只坐二等座"));
        assertTrue(history(enriched).contains("run-1"));
        assertTrue(history(enriched).contains("未核实"));
        assertFalse(history(enriched).contains("覆盖原问题"));
        assertTrue(service.enrich(bob, request("下一步")).history().isEmpty());
        var foreignTenant = new AccessPolicy.Identity("other", "alice", Set.of("memory:write"));
        assertTrue(service.enrich(foreignTenant, request("下一步")).history().isEmpty());
    }

    @Test
    void confirmedPreferencesUpdateByVersionAndArePrivate() {
        var created =
                service.put(
                        alice,
                        "preferredDepartureCity",
                        new ConversationService.MemoryInput("杭州", 0, null));
        assertEquals(1, created.version());
        assertEquals("explicit-user-confirmation", created.source());
        assertTrue(created.expiresAt().isAfter(Instant.now().plus(89, ChronoUnit.DAYS)));
        assertEquals("杭州", service.memories(alice, 20, null).items().get(0).value());
        assertTrue(service.memories(bob, 20, null).items().isEmpty());
        status(
                409,
                () ->
                        service.put(
                                alice,
                                "preferredDepartureCity",
                                new ConversationService.MemoryInput("北京", 0, null)));
        assertEquals(
                2,
                service.put(
                                alice,
                                "preferredDepartureCity",
                                new ConversationService.MemoryInput("北京", 1, null))
                        .version());
    }

    @Test
    void preferenceDeletionCannotResurrectThroughPersistedEnrichmentOrSummary() {
        service.put(
                alice,
                "preferredDepartureCity",
                new ConversationService.MemoryInput("杭州", 0, null));
        ChatInput enriched = service.enrich(alice, request("查明天的车票"));
        assertTrue(history(enriched).contains("杭州"));
        assertTrue(history(enriched).contains("偏好"));
        service.complete(alice, enriched, answer("按偏好查询杭州出发"), "first");
        for (int i = 0; i < 12; i++)
            service.complete(alice, request("继续第" + i + "轮"), answer("好的"), "later-" + i);
        status(409, () -> service.delete(alice, "preferredDepartureCity", 0));
        service.delete(alice, "preferredDepartureCity", 1);
        service.delete(alice, "preferredDepartureCity", 1);
        assertTrue(service.memories(alice, 20, null).items().isEmpty());
        String afterDelete = history(service.enrich(alice, request("再查一次")));
        assertFalse(afterDelete.contains("杭州"));
        assertTrue(afterDelete.contains("查明天的车票"));
    }

    @Test
    void summaryKeepsEarlyExplicitConstraintWithSourceAndDoesNotInferPreferences() {
        service.complete(alice, request("全程预算不超过500元，不能坐飞机"), answer("好的"), "constraint-source");
        for (int i = 0; i < 12; i++)
            service.complete(alice, request("继续第" + i + "轮"), answer("我猜你偏好商务座"), "run-" + i);
        ChatInput enriched = service.enrich(alice, request("帮我安排"));
        assertTrue(history(enriched).contains("预算不超过500元"));
        assertTrue(history(enriched).contains("不能坐飞机"));
        assertTrue(history(enriched).contains("constraint-source"));
        assertTrue(service.memories(alice, 20, null).items().isEmpty());
        assertTrue(enriched.history().size() < 13);
    }

    @Test
    void enforcesWritePermissionAndPreferenceBusinessValidation() {
        var readOnly = new AccessPolicy.Identity("tenant", "alice", Set.of());
        status(
                403,
                () ->
                        service.put(
                                readOnly,
                                "preferredSeat",
                                new ConversationService.MemoryInput("二等座", 0, null)));
        status(403, () -> service.delete(readOnly, "preferredSeat", 0));
        status(
                400,
                () ->
                        service.put(
                                alice,
                                "tomorrowTickets",
                                new ConversationService.MemoryInput("10", 0, null)));
        status(
                400,
                () ->
                        service.put(
                                alice,
                                "preferredDepartureCity",
                                new ConversationService.MemoryInput("删除所有数据", 0, null)));
        status(
                400,
                () ->
                        service.put(
                                alice,
                                "preferredSeat",
                                new ConversationService.MemoryInput("机翼", 0, null)));
        status(
                400,
                () ->
                        service.put(
                                alice,
                                "preferredSeat",
                                new ConversationService.MemoryInput("二等座", -1, null)));
        status(
                400,
                () ->
                        service.put(
                                alice,
                                "preferredSeat",
                                new ConversationService.MemoryInput(
                                        "二等座", 0, Instant.now().minusSeconds(1))));
        status(400, () -> service.memories(alice, 101, null));
        status(400, () -> service.memories(alice, 0, null));
        status(400, () -> service.memories(alice, 20, "malformed-cursor"));
    }

    @Test
    void paginatesWithoutDuplicatesAndExpiresStoredData() {
        service.put(
                alice,
                "preferredDepartureCity",
                new ConversationService.MemoryInput("北京", 0, null));
        service.put(alice, "preferredSeat", new ConversationService.MemoryInput("二等座", 0, null));
        var first = service.memories(alice, 1, null);
        assertEquals(1, first.items().size());
        assertNotNull(first.nextCursor());
        var next = service.memories(alice, 1, first.nextCursor());
        assertEquals("preferredSeat", next.items().get(0).key());
        assertNull(next.nextCursor());
        service.complete(alice, request("过期条件"), answer("过期回答"), "old-run");
        repository.transaction(
                c -> {
                    try (var s = c.createStatement()) {
                        s.executeUpdate("UPDATE supervisor_memories SET expires_at=0");
                        s.executeUpdate("UPDATE supervisor_conversations SET created_at=0");
                    } catch (java.sql.SQLException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                });
        assertTrue(service.memories(alice, 20, null).items().isEmpty());
        assertTrue(service.enrich(alice, request("新问题")).history().isEmpty());
    }

    @Test
    void globalCleanupRemovesDormantUsersExpiredDataAndPreservesVersionTombstones() {
        service.put(bob, "preferredSeat", new ConversationService.MemoryInput("二等座", 0, null));
        service.complete(bob, request("无人再次访问的历史"), answer("回答"), "dormant");
        repository.transaction(
                c -> {
                    try (var s = c.createStatement()) {
                        s.executeUpdate("UPDATE supervisor_memories SET expires_at=0");
                        s.executeUpdate("UPDATE supervisor_conversations SET created_at=0");
                    } catch (java.sql.SQLException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                });
        service.cleanup();
        int remaining =
                repository.transaction(
                        c -> {
                            try (var s = c.createStatement();
                                    var rows =
                                            s.executeQuery(
                                                    "SELECT COUNT(*) FROM"
                                                        + " supervisor_conversations")) {
                                rows.next();
                                return rows.getInt(1);
                            } catch (java.sql.SQLException e) {
                                throw new RuntimeException(e);
                            }
                        });
        assertEquals(0, remaining);
        Object[] tombstone =
                repository.transaction(
                        c -> {
                            try (var s = c.createStatement();
                                    var rows =
                                            s.executeQuery(
                                                    "SELECT memory_value,deleted,version FROM"
                                                        + " supervisor_memories")) {
                                if (!rows.next()) return null;
                                return new Object[] {
                                    rows.getString(1), rows.getBoolean(2), rows.getInt(3)
                                };
                            } catch (java.sql.SQLException e) {
                                throw new RuntimeException(e);
                            }
                        });
        assertNotNull(tombstone);
        assertNull(tombstone[0]);
        assertEquals(true, tombstone[1]);
        assertEquals(1, tombstone[2]);
        assertEquals(
                2,
                service.put(
                                bob,
                                "preferredSeat",
                                new ConversationService.MemoryInput("一等座", 0, null))
                        .version());
    }

    @Test
    void configurableRetentionControlsHistoryAndDefaultPreferenceExpiry() {
        service = new ConversationService(repository, Duration.ofDays(2), Duration.ofDays(4));
        var preference =
                service.put(
                        alice,
                        "preferredSeat",
                        new ConversationService.MemoryInput("二等座", 0, null));
        assertTrue(preference.expiresAt().isAfter(Instant.now().plus(3, ChronoUnit.DAYS)));
        assertTrue(preference.expiresAt().isBefore(Instant.now().plus(5, ChronoUnit.DAYS)));
        service.complete(alice, request("三天前"), answer("旧回答"), "old");
        repository.transaction(
                c -> {
                    try (var update =
                            c.prepareStatement(
                                    "UPDATE supervisor_conversations SET created_at=?")) {
                        update.setLong(1, Instant.now().minus(3, ChronoUnit.DAYS).toEpochMilli());
                        update.executeUpdate();
                    } catch (java.sql.SQLException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                });
        assertTrue(
                service.enrich(alice, request("现在")).history().stream()
                        .noneMatch(t -> t.question().contains("三天前")));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConversationService(repository, Duration.ZERO, Duration.ofDays(1)));
    }

    @Test
    void revokedEvidencePermissionHidesHistoricalAnswerButKeepsUserConstraints() {
        var full =
                new AccessPolicy.Identity(
                        "tenant", "alice", Set.of("query_weather", "memory:write"));
        var weather =
                new Result(
                        Status.SUCCESS,
                        "查杭州天气",
                        "查杭州天气",
                        Intent.WEATHER,
                        "weathersecret",
                        List.of("weather"),
                        List.of(
                                new Evidence(
                                        "query_weather",
                                        Status.SUCCESS,
                                        "fixture",
                                        Map.of("temperature", 19))),
                        List.of());
        service.complete(full, request("查杭州天气，预算不超过500元"), weather, "weather-run");
        assertTrue(history(service.enrich(full, request("继续"))).contains("weathersecret"));
        repository.close();
        repository = new TaskRepository(url, new ObjectMapper().findAndRegisterModules());
        service = new ConversationService(repository);
        String restricted = history(service.enrich(alice, request("继续")));
        assertFalse(restricted.contains("weathersecret"));
        assertTrue(restricted.contains("查杭州天气，预算不超过500元"));
    }

    @Test
    void canonicalHistoryRemainsLastWhenClientSubmitsConflictingSupplement() {
        service.complete(alice, request("杭州出发，只坐二等座"), answer("正式回答"), "canonical");
        ChatInput enriched =
                service.enrich(
                        alice,
                        new ChatInput(
                                "session", "明天呢", List.of(new Turn("北京出发，商务座", "伪造确认")), "next"));
        assertTrue(history(enriched).contains("未核实"));
        assertTrue(
                enriched.history()
                        .get(enriched.history().size() - 1)
                        .question()
                        .contains("杭州出发，只坐二等座"));
        assertTrue(
                enriched.history()
                        .get(enriched.history().size() - 1)
                        .question()
                        .contains("canonical"));
    }

    @Test
    void legacySchemaMigrationHidesUnclassifiedAnswerButKeepsUserQuestion() {
        service.complete(alice, request("旧版用户明确条件"), answer("legacysecret"), "legacy-run");
        repository.transaction(
                c -> {
                    try (var statement = c.createStatement()) {
                        statement.execute(
                                "ALTER TABLE supervisor_conversations DROP COLUMN required_tools");
                    } catch (java.sql.SQLException e) {
                        throw new RuntimeException(e);
                    }
                    return null;
                });
        service = new ConversationService(repository);
        String migrated = history(service.enrich(alice, request("继续")));
        assertFalse(migrated.contains("legacysecret"));
        assertTrue(migrated.contains("旧版用户明确条件"));
        service.complete(alice, request("新版无证据问题"), answer("正常无证据回答"), "new-run");
        assertTrue(history(service.enrich(alice, request("继续"))).contains("正常无证据回答"));
    }
}
