package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.BeansException;

import java.util.List;

class SupervisorConfigurationTest {
    @Test
    void configuredBudgetChangesActualRequestOutcome() {
        try (var web = new WebTestSupport(null, "--supervisor.max-model-calls=2")) {
            assertEquals(2, web.context.getBean(Limits.class).modelCalls());
            var result = web.post(json(new ChatInput("甲", "杭州明天的天气", List.of())));
            assertEquals(200, result.status());
            assertTrue(result.body().contains("BUDGET_EXHAUSTED"));
        }
    }

    @Test
    void invalidBudgetCannotStartContainer() {
        assertThrows(
                BeansException.class,
                () -> {
                    try (var ignored =
                            new WebTestSupport(null, "--supervisor.max-concurrent-requests=0")) {}
                });
    }

    @Test
    void liveModeRequiresCredentialsBeforeServingRequests() {
        Exception error =
                assertThrows(
                        BeansException.class,
                        () -> {
                            try (var ignored =
                                    new WebTestSupport(
                                            null, "--supervisor.mode=live", "--DEMO_API_KEY=")) {}
                        });
        assertTrue(rootMessage(error).contains("DEMO_API_KEY"), rootMessage(error));
    }

    @Test
    void missingAuthenticationConfigurationCannotStartContainer() {
        Exception error =
                assertThrows(
                        BeansException.class,
                        () -> {
                            try (var ignored =
                                    new WebTestSupport(null, "--supervisor.auth-tokens=")) {}
                        });
        assertTrue(rootMessage(error).contains("supervisor.auth-tokens"));
    }

    private String rootMessage(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error.getMessage();
    }
}
