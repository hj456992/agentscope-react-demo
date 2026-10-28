package dev.garden.demo.supervisor.repo.mapper;

import dev.garden.demo.supervisor.pojo.dataobject.SideEffectDO;
import dev.garden.demo.supervisor.pojo.dataobject.SideEffectKeyDO;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** side_effect_operations 的 MyBatis 映射；单条语句方法不在仓储层包事务。 */
public interface SideEffectMapper {
    void createTable();

    int markRunningUnknown();

    SideEffectDO find(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("toolName") String toolName,
            @Param("operationId") String operationId);

    int insert(SideEffectDO row);

    int claimRetry(@Param("downstreamId") String downstreamId);

    int saveOutcome(
            @Param("downstreamId") String downstreamId,
            @Param("operationStatus") String operationStatus,
            @Param("responseText") String responseText,
            @Param("retryAllowed") boolean retryAllowed);

    int bumpLookup(
            @Param("downstreamId") String downstreamId,
            @Param("nextLookup") long nextLookup);

    List<SideEffectKeyDO> findPending(
            @Param("tools") List<String> tools,
            @Param("maxLookups") int maxLookups,
            @Param("now") long now,
            @Param("limit") int limit);
}
