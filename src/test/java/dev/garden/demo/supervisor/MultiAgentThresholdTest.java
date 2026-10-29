package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** 多 Agent 量化门槛按固定顺序给出唯一原因，收益为正才允许。 */
class MultiAgentThresholdTest {
    private static final SupervisorPolicy.MultiAgentPolicy POLICY =
            new SupervisorPolicy.MultiAgentPolicy(
                    SupervisorPolicy.MultiAgentPolicy.Mode.ENFORCE, 2, 4, 2, 2.0, 500);

    @Test
    void twoIndependentSubtasksWithBoundedCostAreAllowed() {
        RoutingDecision decision = MultiAgentGate.evaluate(input(false, 2, 3, 1, 1000, 1500, 600, false), POLICY);
        assertTrue(decision.allowed());
        assertEquals(RoutingDecision.Reason.BENEFIT_POSITIVE, decision.reason());
        assertEquals(3, decision.parallelism());
        assertEquals(1500, decision.estimatedTokens());
        assertEquals(600, decision.latencySavingMs());
    }

    @Test
    void tightlyCoupledTaskIsRejectedEvenWhenOtherMetricsLookGood() {
        RoutingDecision decision = MultiAgentGate.evaluate(input(true, 2, 3, 1, 1000, 1500, 600, false), POLICY);
        assertFalse(decision.allowed());
        assertEquals(RoutingDecision.Reason.TASK_COUPLED, decision.reason());
    }

    @Test
    void oneSubtaskCannotJustifyMultipleAgents() {
        RoutingDecision decision = MultiAgentGate.evaluate(input(false, 1, 1, 1, 1000, 1000, 600, false), POLICY);
        assertFalse(decision.allowed());
        assertEquals(RoutingDecision.Reason.PARALLELISM_TOO_LOW, decision.reason());
    }

    @Test
    void tooManySubtasksAreRejectedByAgentLimit() {
        RoutingDecision decision = MultiAgentGate.evaluate(input(false, 5, 5, 1, 1000, 3000, 600, false), POLICY);
        assertFalse(decision.allowed());
        assertEquals(RoutingDecision.Reason.AGENT_LIMIT_EXCEEDED, decision.reason());
    }

    @Test
    void tooDeepTreeIsRejectedByDepthLimit() {
        RoutingDecision decision = MultiAgentGate.evaluate(input(false, 2, 3, 3, 1000, 1500, 600, false), POLICY);
        assertFalse(decision.allowed());
        assertEquals(RoutingDecision.Reason.DEPTH_LIMIT_EXCEEDED, decision.reason());
    }

    @Test
    void tokenCostBeyondMultiplierIsRejected() {
        RoutingDecision decision = MultiAgentGate.evaluate(input(false, 2, 3, 1, 1000, 2500, 600, false), POLICY);
        assertFalse(decision.allowed());
        assertEquals(RoutingDecision.Reason.COST_EXCEEDS_BENEFIT, decision.reason());
    }

    @Test
    void smallLatencyGainIsRejectedUnlessLatencyIsCritical() {
        assertFalse(
                MultiAgentGate.evaluate(input(false, 2, 3, 1, 1000, 1500, 100, false), POLICY)
                        .allowed());
        assertEquals(
                RoutingDecision.Reason.LATENCY_GAIN_TOO_LOW,
                MultiAgentGate.evaluate(input(false, 2, 3, 1, 1000, 1500, 100, false), POLICY)
                        .reason());
        RoutingDecision critical =
                MultiAgentGate.evaluate(input(false, 2, 3, 1, 1000, 1500, 100, true), POLICY);
        assertTrue(critical.allowed());
        assertEquals(RoutingDecision.Reason.BENEFIT_POSITIVE, critical.reason());
    }

    private static RoutingInput input(
            boolean tightlyCoupled,
            int subtaskCount,
            int parallelism,
            int depth,
            long singleTokens,
            long multiTokens,
            long latencySavingMs,
            boolean latencyCritical) {
        return new RoutingInput(
                tightlyCoupled,
                subtaskCount,
                parallelism,
                depth,
                singleTokens,
                multiTokens,
                latencySavingMs,
                latencyCritical);
    }
}
