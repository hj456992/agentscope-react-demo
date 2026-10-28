package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class KnowledgeTest {
    @Test
    void itineraryMustRetrieveVersionedSourceInsteadOfInventingPolicy() {
        Result result =
                Supervisor.offline(
                                Clock.fixed(Instant.parse("2026-10-28T02:00:00Z"), ZoneOffset.UTC))
                        .run(new ChatInput("knowledge", "明天从杭州到上海玩一天，规划行程", List.of()))
                        .block();
        assertTrue(
                result.evidence().stream()
                        .anyMatch(
                                e ->
                                        e.tool().equals("search_knowledge")
                                                && e.data().containsKey("version")));
        assertTrue(result.answer().contains("travel-guide"));
    }
}
