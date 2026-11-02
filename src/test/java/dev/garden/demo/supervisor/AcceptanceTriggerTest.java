package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.support.AcceptanceTrigger;

import org.junit.jupiter.api.Test;

/** 受控验收提示的白名单、次数上限和固定工具请求形状。 */
class AcceptanceTriggerTest {
    @Test
    void allowlistAcceptsOnlyKnownHintsIgnoringCaseAndSpaces() {
        assertTrue(AcceptanceTrigger.allowed(" SPAWN_CHILD "));
        assertTrue(AcceptanceTrigger.allowed("spawn_child"));
        assertTrue(AcceptanceTrigger.allowed("unknown_tool_3"));
        assertFalse(AcceptanceTrigger.allowed("UNKNOWN_TOOL_4"));
        assertFalse(AcceptanceTrigger.allowed("anything"));
        assertFalse(AcceptanceTrigger.allowed(null));
    }

    @Test
    void perHintLimitsMatchDocumentedBranches() {
        assertEquals(1, AcceptanceTrigger.limit("SPAWN_CHILD"));
        assertEquals(3, AcceptanceTrigger.limit("UNKNOWN_TOOL_3"));
        assertEquals(4, AcceptanceTrigger.limit("REPEAT_TOOL_4"));
        assertEquals(5, AcceptanceTrigger.limit("REPEAT_TOOL_5"));
        assertEquals(2, AcceptanceTrigger.limit("SEMANTIC_LOOP_2"));
        assertEquals(0, AcceptanceTrigger.limit("OTHER"));
    }

    @Test
    void unknownToolAndSchemaHintsProduceDeliberatelyFailingCalls() {
        AcceptanceTrigger.Call unknown = AcceptanceTrigger.call("UNKNOWN_TOOL_1", 1, "2026-11-02", "问题");
        assertEquals("unknown_acceptance_tool", unknown.name());
        assertEquals("controlled", unknown.input().get("probe"));

        AcceptanceTrigger.Call schema =
                AcceptanceTrigger.call("INVALID_SCHEMA_1", 1, "2026-11-02", "问题");
        assertEquals("query_weather", schema.name());
        assertFalse(schema.input().containsKey("date"), schema.input().toString());
    }

    @Test
    void businessAndLoopHintsUseRealToolNamesAndDifferentOrdinals() {
        AcceptanceTrigger.Call same =
                AcceptanceTrigger.call("SAME_CITY", 1, "2026-11-02", "问题");
        assertEquals("query_trains", same.name());
        assertEquals(same.input().get("from"), same.input().get("to"));

        AcceptanceTrigger.Call first =
                AcceptanceTrigger.call("SEMANTIC_LOOP_2", 1, "2026-11-02", "问题");
        AcceptanceTrigger.Call second =
                AcceptanceTrigger.call("SEMANTIC_LOOP_2", 2, "2026-11-02", "问题");
        assertEquals("杭州", first.input().get("city"));
        assertEquals("杭州市", second.input().get("city"));
        assertEquals("2026-11-02", first.input().get("date"));
    }

    @Test
    void spawnHintCarriesTheOriginalQuestionIntoTheTaskMessage() {
        AcceptanceTrigger.Call spawn =
                AcceptanceTrigger.call("SPAWN_CHILD", 1, "2026-11-02", "帮我查天气");
        assertEquals("spawn_agent", spawn.name());
        assertEquals("acceptance_child", spawn.input().get("task_name"));
        assertEquals("weather_agent", spawn.input().get("agent_type"));
        assertTrue(spawn.input().get("message").toString().contains("帮我查天气"));
    }

    @Test
    void unknownHintCannotBeConvertedIntoACall() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AcceptanceTrigger.call("OTHER", 1, "2026-11-02", "问题"));
    }
}
