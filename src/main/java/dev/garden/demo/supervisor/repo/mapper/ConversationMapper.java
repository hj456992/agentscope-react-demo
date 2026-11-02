package dev.garden.demo.supervisor.repo.mapper;

import dev.garden.demo.supervisor.pojo.dataobject.ConversationDO;
import dev.garden.demo.supervisor.pojo.dataobject.MemoryCandidateDO;
import dev.garden.demo.supervisor.pojo.dataobject.MemoryDO;
import dev.garden.demo.supervisor.pojo.dataobject.MemoryVectorDO;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** supervisor_conversations / supervisor_memories 的 MyBatis 映射。 */
public interface ConversationMapper {
    void createConversationsTable();

    void addRequiredToolsColumn();

    void createConversationSessionIndex();

    void createMemoriesTable();

    void createMemoryCandidatesTable();

    void createMemoryVectorsTable();

    int expireAllConversations(@Param("oldest") long oldest);

    int expireConversations(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("oldest") long oldest);

    int expireMemories(@Param("now") long now);

    List<ConversationDO> selectTurns(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);

    int existsConversation(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("runId") String runId);

    int insertConversation(ConversationDO row);

    List<MemoryDO> selectMemories(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("now") long now,
            @Param("after") String after,
            @Param("limit") int limit);

    MemoryDO selectMemory(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("memoryKey") String memoryKey);

    int upsertMemory(@Param("memory") MemoryDO memory, @Param("confirmedAt") long confirmedAt);

    int deleteMemory(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("memoryKey") String memoryKey);

    int insertMemoryCandidate(MemoryCandidateDO row);

    MemoryCandidateDO selectMemoryCandidate(@Param("candidateId") String candidateId);

    List<MemoryCandidateDO> selectMemoryCandidates(
            @Param("tenantId") String tenantId, @Param("userId") String userId);

    int decideMemoryCandidate(
            @Param("candidateId") String candidateId,
            @Param("status") String status,
            @Param("decidedAt") long decidedAt,
            @Param("decidedBy") String decidedBy,
            @Param("reason") String reason,
            @Param("appliedVersion") Integer appliedVersion);

    int countMemoryCandidates(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("status") String status);

    int upsertMemoryVector(MemoryVectorDO vector);

    List<MemoryVectorDO> selectMemoryVectors(
            @Param("tenantId") String tenantId, @Param("userId") String userId);

    int deleteMemoryVector(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("memoryKey") String memoryKey);

    int countActiveMemoryVectors(
            @Param("tenantId") String tenantId, @Param("userId") String userId);

    int countMemoryTombstones(
            @Param("tenantId") String tenantId, @Param("userId") String userId);

    int purgeExpiredMemoryVectors(@Param("now") long now);
}
