package dev.garden.demo.supervisor.repo.mapper;

import dev.garden.demo.supervisor.pojo.dataobject.ConversationDO;
import dev.garden.demo.supervisor.pojo.dataobject.MemoryDO;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** supervisor_conversations / supervisor_memories 的 MyBatis 映射。 */
public interface ConversationMapper {
    void createConversationsTable();

    void addRequiredToolsColumn();

    void createConversationSessionIndex();

    void createMemoriesTable();

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
}
