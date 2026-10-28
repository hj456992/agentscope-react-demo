package dev.garden.demo.supervisor.repo.mapper;

import dev.garden.demo.supervisor.pojo.dataobject.InstructionDO;
import dev.garden.demo.supervisor.pojo.dataobject.QuotaWindowDO;
import dev.garden.demo.supervisor.pojo.dataobject.SessionDO;
import dev.garden.demo.supervisor.pojo.dataobject.TaskRowDO;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/** supervisor_tasks / instructions / windows / sessions 的 MyBatis 映射。 */
public interface TaskMapper {
    void createTasksTable();

    void createInstructionsTable();

    void createWindowsTable();

    void createSessionsTable();

    String selectPayload(@Param("runId") String runId);

    List<String> selectAllPayloads();

    int upsertTask(TaskRowDO row);

    InstructionDO selectInstruction(
            @Param("runId") String runId, @Param("instructionId") String instructionId);

    int insertInstruction(InstructionDO row);

    QuotaWindowDO selectWindow(@Param("windowKey") String windowKey);

    int upsertWindow(QuotaWindowDO row);

    String selectCurrentSession(
            @Param("tenantId") String tenantId,
            @Param("userId") String userId,
            @Param("sessionId") String sessionId);

    int upsertSession(SessionDO row);

    int deleteInstructions(@Param("runId") String runId);

    int deleteTask(@Param("runId") String runId);

    int deleteOrphanSessions();
}
