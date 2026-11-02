package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.config.BusinessRuleSetting;
import dev.garden.demo.supervisor.config.SupervisorPolicySettings;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.*;

/** 业务规则来自声明列表，执行顺序和工具范围都由配置决定。 */
class BusinessRuleDeclarationTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 29);

    @Test
    void ruleOnlyAppliesToDeclaredTools() {
        SupervisorPolicy policy = policy(rule("CUSTOM_DATE", "query_weather"));
        assertTrue(
                BusinessRules.validate(
                                "query_weather",
                                Map.of("date", "2026-10-28"),
                                TODAY,
                                policy.business())
                        .isPresent());
        assertTrue(
                BusinessRules.validate(
                                "query_trains",
                                Map.of("date", "2026-10-28"),
                                TODAY,
                                policy.business())
                        .isEmpty());
    }

    @Test
    void firstMatchingRuleWins() {
        SupervisorPolicy policy =
                policy(
                        rule("FIRST", "query_weather"),
                        new SupervisorPolicy.BusinessRule(
                                "SECOND",
                                Set.of("query_weather"),
                                SupervisorPolicy.BusinessRule.Type.DATE_NOT_BEFORE_TODAY,
                                "date",
                                null,
                                "第二条"));
        BusinessRuleViolation violation =
                BusinessRules.validate(
                                "query_weather",
                                Map.of("date", "2026-10-28"),
                                TODAY,
                                policy.business())
                        .orElseThrow();
        assertEquals("FIRST", violation.ruleId());
    }

    @Test
    void configuredSettingsReplaceDefaultRules() {
        SupervisorPolicy policy =
                settings(
                                List.of(
                                        new BusinessRuleSetting(
                                                "SAME_CITY_ONLY",
                                                "query_trains",
                                                "field_not_equal",
                                                "from",
                                                "to",
                                                "请改城市")))
                        .toPolicy();
        assertEquals(1, policy.business().rules().size());
        SupervisorPolicy.BusinessRule rule = policy.business().rules().get(0);
        assertEquals("SAME_CITY_ONLY", rule.id());
        assertEquals(SupervisorPolicy.BusinessRule.Type.FIELD_NOT_EQUAL, rule.type());
        assertEquals("to", rule.otherField());
        assertEquals(Set.of("query_trains"), rule.tools());
    }

    @Test
    void emptySettingsFallBackToDefaultRules() {
        SupervisorPolicy policy = settings(List.of()).toPolicy();
        assertEquals(SupervisorPolicy.BusinessRule.defaults(), policy.business().rules());
    }

    @Test
    void unknownRuleTypeIsRejected() {
        SupervisorPolicySettings invalid =
                settings(
                        List.of(
                                new BusinessRuleSetting(
                                        "BROKEN",
                                        "query_weather",
                                        "date_magic",
                                        "date",
                                        null,
                                        "无效")));
        assertThrows(IllegalArgumentException.class, invalid::toPolicy);
    }

    private static SupervisorPolicy policy(SupervisorPolicy.BusinessRule... rules) {
        return SupervisorPolicy.defaults()
                .withBusiness(
                        new SupervisorPolicy.BusinessPolicy(
                                true, 30, List.of(rules)));
    }

    private static SupervisorPolicy.BusinessRule rule(String id, String tool) {
        return new SupervisorPolicy.BusinessRule(
                id,
                Set.of(tool),
                SupervisorPolicy.BusinessRule.Type.DATE_NOT_BEFORE_TODAY,
                "date",
                null,
                "请改日期");
    }

    private static SupervisorPolicySettings settings(List<BusinessRuleSetting> rules) {
        return new SupervisorPolicySettings(
                true,
                30,
                false,
                false,
                "query_weather,query_trains",
                false,
                2,
                2,
                2,
                6,
                "OFF",
                2,
                4,
                2,
                2.0,
                500,
                false,
                8,
                false,
                6,
                0.6,
                3,
                2000,
                false,
                1.0,
                "KEEP_CONDITION_VERBATIM",
                false,
                64,
                8192,
                false,
                3,
                true,
                false,
                12,
                0.85,
                2,
                false,
                rules);
    }
}
