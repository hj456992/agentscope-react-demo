package dev.garden.demo.supervisor;

import dev.garden.demo.supervisor.pojo.dataobject.MemoryDO;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 测试专用的数据准备语句；生产代码不包含任何手写 SQL。 */
public interface TestSqlMapper {
    @Update("UPDATE supervisor_memories SET expires_at=0")
    int expireAllMemories();

    @Update("UPDATE supervisor_conversations SET created_at=0")
    int expireAllConversations();

    @Update("UPDATE supervisor_conversations SET created_at=#{createdAt}")
    int ageConversations(@Param("createdAt") long createdAt);

    @Select("SELECT COUNT(*) FROM supervisor_conversations")
    int countConversations();

    @Select("SELECT memory_value,deleted,version FROM supervisor_memories")
    List<MemoryDO> memories();

    @Update("ALTER TABLE supervisor_conversations DROP COLUMN required_tools")
    void dropRequiredToolsColumn();
}
