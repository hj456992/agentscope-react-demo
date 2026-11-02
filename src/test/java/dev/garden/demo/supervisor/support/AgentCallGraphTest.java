package dev.garden.demo.supervisor.support;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 协作调用图只阻止回边，不阻止合法的树内新边。 */
class AgentCallGraphTest {
    @Test
    void directAndIndirectCyclesAreRejected() {
        AgentCallGraph.Decision first = AgentCallGraph.record(null, "/root", "/root/a");
        assertFalse(first.cycle());
        AgentCallGraph.Decision second =
                AgentCallGraph.record(first.value(), "/root/a", "/root/b");
        assertFalse(second.cycle());
        AgentCallGraph.Decision third =
                AgentCallGraph.record(second.value(), "/root/b", "/root");
        assertTrue(third.cycle());
        assertEquals(second.value(), third.value());

        AgentCallGraph.Decision self = AgentCallGraph.record(null, "/root", "/root");
        assertTrue(self.cycle());
    }

    @Test
    void independentEdgeIsAccepted() {
        AgentCallGraph.Decision first = AgentCallGraph.record(null, "/root", "/root/a");
        AgentCallGraph.Decision second =
                AgentCallGraph.record(first.value(), "/root", "/root/b");
        assertFalse(second.cycle());
        assertTrue(AgentCallGraph.read(second.value()).get("/root").contains("/root/a"));
        assertTrue(AgentCallGraph.read(second.value()).get("/root").contains("/root/b"));
    }

    @Test
    void capacityLimitRejectsNewEdgeWithoutChangingSavedGraph() {
        String saved = null;
        for (int index = 0; index < AgentCallGraph.MAX_EDGES; index++) {
            AgentCallGraph.Decision decision =
                    AgentCallGraph.record(saved, "/root/a" + index, "/root/b" + index);
            assertFalse(decision.cycle(), "第 " + index + " 条边不应形成环");
            saved = decision.value();
        }
        AgentCallGraph.Decision overflow =
                AgentCallGraph.record(saved, "/root/extra", "/root/other");
        assertTrue(overflow.cycle());
        assertEquals(saved, overflow.value());
    }

    @Test
    void malformedGraphIsRejected() {
        assertThrows(
                IllegalStateException.class,
                () -> AgentCallGraph.record("{not-json", "/root", "/root/a"));
    }
}
