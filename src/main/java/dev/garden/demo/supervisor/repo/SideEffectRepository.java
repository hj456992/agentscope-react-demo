package dev.garden.demo.supervisor.repo;

import dev.garden.demo.supervisor.pojo.dataobject.SideEffectDO;
import dev.garden.demo.supervisor.pojo.dataobject.SideEffectKeyDO;
import dev.garden.demo.supervisor.repo.mapper.SideEffectMapper;

import java.util.List;

/**
 * 副作用账本仓储。构造时完成建表并把遗留 RUNNING 标为 UNKNOWN，恢复语义与旧实现一致。
 *
 * <p>这里的语句故意不包事务：claim / RUNNING 必须在调用下游前立即落盘。
 */
public final class SideEffectRepository {
    private final Database database;
    private final SideEffectMapper mapper;

    public SideEffectRepository(Database database) {
        this.database = database;
        this.mapper = database.mapper(SideEffectMapper.class);
        mapper.createTable();
        // 上次进程遗留的 RUNNING 只能查询，不把它当首次操作重放。
        mapper.markRunningUnknown();
    }

    public Database database() {
        return database;
    }

    public SideEffectDO find(String tenant, String user, String tool, String operation) {
        return mapper.find(tenant, user, tool, operation);
    }

    public boolean insert(SideEffectDO row) {
        return mapper.insert(row) > 0;
    }

    public void claimRetry(String downstreamId) {
        mapper.claimRetry(downstreamId);
    }

    public void saveOutcome(
            String downstreamId, String status, String response, boolean retryAllowed) {
        mapper.saveOutcome(downstreamId, status, response, retryAllowed);
    }

    public void bumpLookup(String downstreamId, long nextLookup) {
        mapper.bumpLookup(downstreamId, nextLookup);
    }

    public List<SideEffectKeyDO> findPending(
            List<String> tools, int maxLookups, long now, int limit) {
        return mapper.findPending(tools, maxLookups, now, limit);
    }
}
