package dev.garden.demo.supervisor.repo;

import dev.garden.demo.supervisor.pojo.dataobject.ConversationDO;
import dev.garden.demo.supervisor.pojo.dataobject.MemoryDO;
import dev.garden.demo.supervisor.repo.mapper.ConversationMapper;

import java.util.List;

/** 会话与偏好持久化仓储，只执行 MyBatis 映射，不声明事务。 */
public final class ConversationRepository {
    private final Database database;
    private final ConversationMapper mapper;
    private final JsonCodec codec;

    public ConversationRepository(Database database, JsonCodec codec) {
        this.database = database;
        this.codec = codec;
        this.mapper = database.mapper(ConversationMapper.class);
        initializeSchema();
    }

    /** 每次构造都执行建表与旧库迁移，保留既有数据库的可重复初始化行为。 */
    public void initializeSchema() {
        mapper.createConversationsTable();
        mapper.addRequiredToolsColumn();
        mapper.createConversationSessionIndex();
        mapper.createMemoriesTable();
    }

    public Database database() {
        return database;
    }

    public JsonCodec codec() {
        return codec;
    }

    public int expireAllConversations(long oldest) {
        return mapper.expireAllConversations(oldest);
    }

    public int expireConversations(String tenantId, String userId, long oldest) {
        return mapper.expireConversations(tenantId, userId, oldest);
    }

    public int expireMemories(long now) {
        return mapper.expireMemories(now);
    }

    public List<ConversationDO> turns(String tenantId, String userId, String sessionId) {
        return mapper.selectTurns(tenantId, userId, sessionId);
    }

    public boolean exists(String tenantId, String userId, String runId) {
        return mapper.existsConversation(tenantId, userId, runId) > 0;
    }

    public void insert(ConversationDO row) {
        mapper.insertConversation(row);
    }

    public List<MemoryDO> memories(
            String tenantId, String userId, long now, String after, int limit) {
        return mapper.selectMemories(tenantId, userId, now, after, limit);
    }

    public MemoryDO memory(String tenantId, String userId, String key) {
        return mapper.selectMemory(tenantId, userId, key);
    }

    public void upsertMemory(MemoryDO memory, long confirmedAt) {
        mapper.upsertMemory(memory, confirmedAt);
    }

    public void deleteMemory(String tenantId, String userId, String key) {
        mapper.deleteMemory(tenantId, userId, key);
    }
}
