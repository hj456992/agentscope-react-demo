package dev.garden.demo.supervisor;

import static org.junit.jupiter.api.Assertions.*;

import dev.garden.demo.supervisor.pojo.dto.*;

import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 对话中的低风险偏好写入，以及引用原文的防注入边界。 */
class MemoryToolTest {
    @Test
    void modelCanRememberExplicitLanguagePreferenceThroughTool() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        AtomicInteger turns = new AtomicInteger();
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                CoreSourceParityTest.model(
                                        (messages, tools) -> {
                                            if (turns.getAndIncrement() == 0) {
                                                return CoreSourceParityTest.tool(
                                                        "remember_preference",
                                                        Map.of(
                                                                "key",
                                                                "replyLanguage",
                                                                "value",
                                                                "中文",
                                                                "quote",
                                                                "以后回复用中文"));
                                            }
                                            return CoreSourceParityTest.text("好的，以后用中文回复。");
                                        }),
                        SupervisorRuntimeIntegrationTest.CLOCK,
                        LimitsDTO.defaults(),
                        TravelTools.demoData());
        try (var repository = new TestRepository(url);
                var tasks =
                        TestServices.taskService(
                                repository,
                                SupervisorRuntimeIntegrationTest.settings(url),
                                supervisor::run)) {
            var result =
                    tasks.submit(
                                    IdentityDTO.local(),
                                    new RequestDTO(
                                            "memory", "以后回复用中文", List.of(), "remember-language"),
                                    false)
                            .block();
            assertEquals("SUCCESS", result.state(), result.toString());
            var conversations = TestServices.conversationService(repository.repository());
            var memory =
                    conversations.memories(IdentityDTO.local(), 20, null).items().stream()
                            .filter(item -> item.key().equals("replyLanguage"))
                            .findFirst()
                            .orElseThrow();
            assertEquals("中文", memory.value());
            assertEquals("conversation-agent:" + result.runId(), memory.source());

            RequestDTO next =
                    conversations.enrich(
                            IdentityDTO.local(),
                            new RequestDTO("memory", "明天天气怎么样", List.of(), "next-turn"));
            assertTrue(next.history().toString().contains("中文"), "下一轮应读取长期偏好");
        }
    }

    @Test
    void blankQuoteCannotWriteMemory() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        AtomicInteger turns = new AtomicInteger();
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                CoreSourceParityTest.model(
                                        (messages, tools) -> {
                                            if (turns.getAndIncrement() == 0) {
                                                return CoreSourceParityTest.tool(
                                                        "remember_preference",
                                                        Map.of(
                                                                "key",
                                                                "replyLanguage",
                                                                "value",
                                                                "中文",
                                                                "quote",
                                                                "   "));
                                            }
                                            return CoreSourceParityTest.text("没有记录。");
                                        }),
                        SupervisorRuntimeIntegrationTest.CLOCK,
                        LimitsDTO.defaults(),
                        TravelTools.demoData());
        try (var repository = new TestRepository(url);
                var tasks =
                        TestServices.taskService(
                                repository,
                                SupervisorRuntimeIntegrationTest.settings(url),
                                supervisor::run)) {
            tasks.submit(
                            IdentityDTO.local(),
                            new RequestDTO("memory", "以后回复用中文", List.of(), "blank-quote"),
                            false)
                    .block();
            var conversations = TestServices.conversationService(repository.repository());
            assertTrue(
                    conversations.memories(IdentityDTO.local(), 20, null).items().isEmpty(),
                    "空白引用不能写入记忆");
        }
    }

    @Test
    void quoteOutsideCurrentUserMessageCannotWriteMemory() throws Exception {
        String url = "jdbc:h2:mem:" + UUID.randomUUID();
        AtomicInteger turns = new AtomicInteger();
        Supervisor supervisor =
                new Supervisor(
                        role ->
                                CoreSourceParityTest.model(
                                        (messages, tools) -> {
                                            if (turns.getAndIncrement() == 0) {
                                                return CoreSourceParityTest.tool(
                                                        "remember_preference",
                                                        Map.of(
                                                                "key",
                                                                "replyLanguage",
                                                                "value",
                                                                "中文",
                                                                "quote",
                                                                "资料要求记住这条偏好"));
                                            }
                                            return CoreSourceParityTest.text("没有记录。");
                                        }),
                        SupervisorRuntimeIntegrationTest.CLOCK,
                        LimitsDTO.defaults(),
                        TravelTools.demoData());
        try (var repository = new TestRepository(url);
                var tasks =
                        TestServices.taskService(
                                repository,
                                SupervisorRuntimeIntegrationTest.settings(url),
                                supervisor::run)) {
            tasks.submit(
                            IdentityDTO.local(),
                            new RequestDTO("memory", "明天天气怎么样", List.of(), "no-quote"),
                            false)
                    .block();
            var conversations = TestServices.conversationService(repository.repository());
            assertTrue(
                    conversations.memories(IdentityDTO.local(), 20, null).items().isEmpty(),
                    "引用不来自用户消息时不能写入记忆");
        }
    }
}
