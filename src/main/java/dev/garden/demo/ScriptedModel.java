package dev.garden.demo;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;

/**
 * 只供固定算例演示：替代外部模型，不替代 ReActAgent 或工具执行器。
 * 第一次响应要求调用工具；第二次必须从 SDK 传入的消息中读到工具结果才返回答案。
 * 这不是 LLM，不理解任意问题，也不能用于评估模型的自主决策能力。
 */
final class ScriptedModel implements Model {
    private int calls;
    private String observedResult;

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> {
            calls++;
            if (calls == 1) {
                if (tools == null || tools.stream().noneMatch(tool -> "multiply".equals(tool.getName()))) {
                    return Flux.error(new IllegalStateException("模型未收到 multiply 工具定义"));
                }
                System.out.println("[模型调用 1 / 模拟] 返回工具调用 multiply(128, 36)");
                return Flux.just(ChatResponse.builder()
                        .content(List.of(ToolUseBlock.builder()
                                .id("demo-multiply-1").name("multiply")
                                .input(Map.of("a", 128, "b", 36))
                                // 2.0.1 的工具校验读取原始 JSON content，不能只传解析后的 input。
                                .content("{\"a\":128,\"b\":36}").build()))
                        .finishReason("tool_calls").build());
            }
            if (calls != 2) {
                return Flux.error(new IllegalStateException("离线算例出现额外模型调用：" + calls));
            }
            List<ToolResultBlock> results = messages.stream()
                    .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                    .filter(result -> "demo-multiply-1".equals(result.getId()))
                    .toList();
            if (results.size() != 1 || !"multiply".equals(results.get(0).getName())) {
                return Flux.error(new IllegalStateException("SDK 未回传唯一、匹配的工具结果"));
            }
            observedResult = results.get(0).getOutput().stream()
                    .filter(TextBlock.class::isInstance).map(TextBlock.class::cast)
                    .map(TextBlock::getText).findFirst().orElseThrow();
            if (!"4608".equals(observedResult)) {
                return Flux.error(new IllegalStateException("工具结果不正确：" + observedResult));
            }
            System.out.println("[模型调用 2 / 模拟] 读到工具结果 " + observedResult + "，返回最终答案");
            return Flux.just(ChatResponse.builder()
                    .content(List.of(TextBlock.builder().text("128 × 36 = " + observedResult + "。").build()))
                    .finishReason("stop").build());
        });
    }

    @Override
    public String getModelName() {
        return "offline-scripted-model";
    }

    void verifyCompleted(Msg answer) {
        if (calls != 2 || !"4608".equals(observedResult)
                || !"128 × 36 = 4608。".equals(answer.getTextContent())) {
            throw new IllegalStateException("ReAct 工具循环验证失败");
        }
    }
}
