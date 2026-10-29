package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.pojo.dto.*;

import org.junit.jupiter.api.Test;

class SupervisorPolicyInjectionTest {
    @Test
    void localControlWithoutExplicitPolicyKeepsNewCapabilitiesOff() {
        SupervisorPolicy policy = RunControl.local(LimitsDTO.defaults()).policy();
        assertEquals(SupervisorPolicy.defaults(), policy);
        assertFalse(policy.business().enabled());
        assertFalse(policy.factCheck().enabled());
        assertFalse(policy.correction().enabled());
        assertEquals(SupervisorPolicy.MultiAgentPolicy.Mode.OFF, policy.multiAgent().mode());
        assertFalse(policy.topic().enabled());
        assertFalse(policy.summary().alertEnabled());
    }

    @Test
    void explicitPolicyIsVisibleThroughEveryLocalChildControl() {
        SupervisorPolicy policy =
                SupervisorPolicy.defaults()
                        .withBusiness(new SupervisorPolicy.BusinessPolicy(true, 10))
                        .withCorrection(
                                new SupervisorPolicy.CorrectionPolicy(true, 1, 1, 1, 1));
        RunControl control = RunControl.local(LimitsDTO.defaults(), policy);
        assertSame(policy, control.policy());
    }

    @Test
    void springBindsEveryPolicySection() {
        try (var web =
                new WebTestSupport(
                        null,
                        "--supervisor.policy.business-rules-enabled=true",
                        "--supervisor.policy.business-max-future-days=7",
                        "--supervisor.policy.fact-check-enabled=true",
                        "--supervisor.policy.fact-high-risk-tools=query_weather",
                        "--supervisor.policy.correction-limits-enabled=true",
                        "--supervisor.policy.max-consecutive-schema-errors=3",
                        "--supervisor.policy.max-tool-not-found=4",
                        "--supervisor.policy.max-business-violations=5",
                        "--supervisor.policy.max-total-corrections=9",
                        "--supervisor.policy.multi-agent-mode=ENFORCE",
                        "--supervisor.policy.multi-agent-min-parallelism=3",
                        "--supervisor.policy.multi-agent-max-agents=5",
                        "--supervisor.policy.multi-agent-max-depth=4",
                        "--supervisor.policy.topic-segmentation-enabled=true",
                        "--supervisor.policy.topic-recent-turns=8",
                        "--supervisor.policy.topic-min-confidence=0.75",
                        "--supervisor.policy.retry-max-attempts=2",
                        "--supervisor.policy.retry-backoff-cap-ms=1000",
                        "--supervisor.policy.summary-fidelity-alert-enabled=true",
                        "--supervisor.policy.summary-fidelity-threshold=0.8")) {
            SupervisorPolicy policy = web.context.getBean(SupervisorPolicy.class);
            assertTrue(policy.business().enabled());
            assertEquals(7, policy.business().maxFutureDays());
            assertTrue(policy.factCheck().enabled());
            assertEquals(java.util.Set.of("query_weather"), policy.factCheck().highRiskTools());
            assertTrue(policy.correction().enabled());
            assertEquals(3, policy.correction().maxConsecutiveSchemaErrors());
            assertEquals(4, policy.correction().maxToolNotFound());
            assertEquals(5, policy.correction().maxBusinessViolations());
            assertEquals(9, policy.correction().maxTotalCorrections());
            assertEquals(
                    SupervisorPolicy.MultiAgentPolicy.Mode.ENFORCE, policy.multiAgent().mode());
            assertEquals(3, policy.multiAgent().minParallelism());
            assertEquals(5, policy.multiAgent().maxAgents());
            assertEquals(4, policy.multiAgent().maxDepth());
            assertTrue(policy.topic().enabled());
            assertEquals(8, policy.topic().recentTurns());
            assertEquals(0.75, policy.topic().minConfidence(), 0.0001);
            assertEquals(2, policy.failure().maxAttempts());
            assertEquals(1000, policy.failure().backoffCapMs());
            assertTrue(policy.summary().alertEnabled());
            assertEquals(0.8, policy.summary().fidelityThreshold(), 0.0001);
        }
    }
}
