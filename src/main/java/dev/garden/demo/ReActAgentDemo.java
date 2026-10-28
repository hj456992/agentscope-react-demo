package dev.garden.demo;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import java.time.Duration;
import java.util.Arrays;

/** 从这里阅读：注册工具 → 创建 Agent → 传入问题 → SDK 执行工具循环 → 输出答案。 */
public final class ReActAgentDemo {
    static final String QUESTION = "请调用乘法工具计算 128 × 36，并用中文回答。";

    public static void main(String[] args) {
        boolean live = args.length > 0 && "--live".equals(args[0]);
        if (args.length > 0 && !live && !(args.length == 1 && "--offline".equals(args[0]))) {
            throw new IllegalArgumentException("用法：java -jar target/agentscope-react-demo.jar [--offline | --live [问题]]");
        }
        String question = live && args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : QUESTION;
        try {
            Model model = live ? liveModel() : new ScriptedModel();
            System.out.println(live ? "模式：真实模型" : "模式：离线脚本模型（真实 AgentScope 工具循环，无网络请求）");

            // 1. 工具是普通 Java 方法；注解生成模型可见的工具名称、描述和参数 schema。
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new MathTools());

            // 2. ReActAgent 负责循环：请求模型 → 执行工具 → 回传结果 → 再请求模型。
            //    SDK 类名是 ReActAgent，ReAct = Reasoning + Acting，与前端 React 无关。
            ReActAgent agent = ReActAgent.builder()
                    .name("math-assistant")
                    .sysPrompt("你是计算助手。涉及乘法时使用 multiply 工具，依据工具结果用中文简短回答。")
                    .model(model)
                    .toolkit(toolkit)
                    .maxIters(4)
                    .maxRetries(1)
                    .modelExecutionConfig(ExecutionConfig.builder().maxAttempts(1).build())
                    .enableMetaTool(false)
                    .enableTaskList(false)
                    .build();

            // 3. call 返回 Mono<Msg>。命令行程序在这里阻塞等待，WebFlux 请求线程不应照搬 block。
            Msg input = Msg.builder().name("user").role(MsgRole.USER).textContent(question).build();
            System.out.println("用户：" + question);
            Msg answer = agent.call(input).block(Duration.ofSeconds(60));
            if (answer == null || answer.getTextContent().isBlank()) {
                throw new IllegalStateException("Agent 未返回文本答案");
            }
            System.out.println("Agent：" + answer.getTextContent());

            // 离线运行同时做一次集成冒烟检查，确认答案来自工具回传而非跳过工具。
            if (model instanceof ScriptedModel scripted) {
                scripted.verifyCompleted(answer);
                System.out.println("验证通过：2 次模型调用 → 1 次工具执行 → 工具结果进入第二次模型输入。");
            }
        } finally {
            // 仅用于这个独立 CLI 的退出清理；共享服务中不要每次请求关闭全局传输。
            HttpTransportFactory.shutdown();
        }
    }

    private static Model liveModel() {
        // 使用供应商提供的兼容 Chat Completions 接口；不读取项目私有配置或硬编码密钥。
        return OpenAIChatModel.builder()
                .apiKey(requiredEnv("DEMO_API_KEY"))
                .baseUrl(requiredEnv("DEMO_BASE_URL"))
                .modelName(requiredEnv("DEMO_MODEL"))
                .stream(false)
                .build();
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("缺少环境变量 " + name + "，配置方式见 README.md");
        }
        return value;
    }

    /** 必须是 public，SDK 才能通过反射调用工具方法。 */
    public static final class MathTools {
        @Tool(name = "multiply", description = "计算两个整数的乘积，返回精确的十进制结果。")
        public ToolResultBlock multiply(
                @ToolParam(name = "a", description = "第一个整数") long a,
                @ToolParam(name = "b", description = "第二个整数") long b) {
            String result = java.math.BigInteger.valueOf(a)
                    .multiply(java.math.BigInteger.valueOf(b)).toString();
            System.out.printf("[工具执行] multiply(a=%d, b=%d) → %s%n", a, b, result);
            return ToolResultBlock.text(result);
        }
    }
}
