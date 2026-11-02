package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;
import dev.garden.demo.supervisor.pojo.dto.MemoryCandidateDTO;
import dev.garden.demo.supervisor.pojo.param.MemoryCandidateDecisionParam;
import dev.garden.demo.supervisor.pojo.param.MemoryCandidateParam;
import dev.garden.demo.supervisor.pojo.req.MemoryReq;
import dev.garden.demo.supervisor.pojo.param.MemorySearchParam;
import dev.garden.demo.supervisor.repo.ConversationRepository;
import dev.garden.demo.supervisor.repo.Database;
import dev.garden.demo.supervisor.repo.JsonCodec;
import dev.garden.demo.supervisor.service.ConversationService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/** 记忆候选、本地向量、删除墓碑与保留期事实。 */
class MemoryGovernanceTest {
    private static final IdentityDTO USER =
            new IdentityDTO("tenant", "memory-user", Set.of("memory:write", "query_trains"));

    @TempDir Path directory;
    private Database database;
    private ConversationService conversations;

    @BeforeEach
    void setUp() {
        database = TestServices.database("jdbc:h2:file:" + directory.resolve("memory"));
        conversations =
                new ConversationService(
                        new ConversationRepository(
                                database, new JsonCodec(new ObjectMapper())),
                        Duration.ofDays(30),
                        Duration.ofDays(90),
                        SupervisorPolicy.defaults().topic());
    }

    @AfterEach
    void tearDown() {
        database.close();
    }

    @Test
    void explicitPreferenceIsIndexedLocallyAndSearchable() {
        conversations.put(
                USER,
                "preferredDepartureCity",
                new MemoryReq("上海", 0, Instant.now().plus(Duration.ofDays(10))));

        var hits =
                conversations.search(USER, new MemorySearchParam("上海出发", 5));
        assertEquals(1, hits.size());
        assertEquals("preferredDepartureCity", hits.get(0).key());
        assertEquals("local-hash-32", hits.get(0).embedding());
        assertEquals(1, conversations.governance(USER).activeVectors());
    }

    @Test
    void candidateDoesNotWriteUntilApprovedAndRejectedCandidateNeverWrites() {
        MemoryCandidateDTO pending =
                conversations.propose(
                        USER,
                        new MemoryCandidateParam(
                                "preferredDepartureCity",
                                "杭州",
                                "用户主动提交",
                                0,
                                Instant.now().plus(Duration.ofDays(5))));
        assertEquals("PENDING", pending.status());
        assertTrue(conversations.memories(USER, 20, null).items().isEmpty());
        assertTrue(conversations.search(USER, new MemorySearchParam("杭州", 5)).isEmpty());

        MemoryCandidateDTO approved =
                conversations.decideCandidate(
                        USER,
                        new MemoryCandidateDecisionParam(
                                pending.candidateId(), "APPROVE", "核对通过"));
        assertEquals("APPROVED", approved.status());
        assertEquals(1, approved.appliedVersion());
        var memories = conversations.memories(USER, 20, null).items();
        assertEquals(1, memories.size());
        assertTrue(memories.get(0).source().startsWith("reviewed-candidate:"));
        assertEquals(1, conversations.search(USER, new MemorySearchParam("杭州", 5)).size());

        MemoryCandidateDTO rejected =
                conversations.propose(
                        USER,
                        new MemoryCandidateParam(
                                "preferredSeat",
                                "二等座",
                                "用户主动提交",
                                0,
                                Instant.now().plus(Duration.ofDays(5))));
        conversations.decideCandidate(
                USER,
                new MemoryCandidateDecisionParam(rejected.candidateId(), "REJECT", "不需要"));
        assertTrue(conversations.memories(USER, 20, null).items().stream()
                .noneMatch(memory -> memory.key().equals("preferredSeat")));
    }

    @Test
    void candidateVersionChangeRejectsApprovalInsteadOfOverwriting() {
        conversations.put(
                USER,
                "preferredDepartureCity",
                new MemoryReq("上海", 0, Instant.now().plus(Duration.ofDays(10))));
        MemoryCandidateDTO pending =
                conversations.propose(
                        USER,
                        new MemoryCandidateParam(
                                "preferredDepartureCity",
                                "杭州",
                                "用户主动提交",
                                1,
                                Instant.now().plus(Duration.ofDays(5))));
        conversations.put(
                USER,
                "preferredDepartureCity",
                new MemoryReq("北京", 1, Instant.now().plus(Duration.ofDays(10))));

        ResponseStatusException conflict =
                assertThrows(
                        ResponseStatusException.class,
                        () ->
                                conversations.decideCandidate(
                                        USER,
                                        new MemoryCandidateDecisionParam(
                                                pending.candidateId(), "APPROVE", "核对通过")));
        assertEquals(HttpStatus.CONFLICT, conflict.getStatusCode());
        assertFalse(
                conversations.memories(USER, 20, null).items().stream()
                        .anyMatch(memory -> memory.value().equals("杭州")));
    }

    @Test
    void deleteRemovesContentFromListAndVectorSearchAndLeavesTombstone() {
        conversations.put(
                USER,
                "preferredDepartureCity",
                new MemoryReq("上海", 0, Instant.now().plus(Duration.ofDays(10))));
        conversations.delete(USER, "preferredDepartureCity", 1);

        assertTrue(conversations.memories(USER, 20, null).items().isEmpty());
        assertTrue(conversations.search(USER, new MemorySearchParam("上海", 5)).isEmpty());
        var governance = conversations.governance(USER);
        assertEquals(0, governance.activeVectors());
        assertTrue(governance.tombstones() >= 1);
        assertTrue(governance.crossReplica().contains("未声明"));
    }
}
