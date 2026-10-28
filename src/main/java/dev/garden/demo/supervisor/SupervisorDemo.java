package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.time.Clock;
import java.util.*;

/** 命令行入口。默认离线，只有明确传入 --live 才请求真实模型。 */
public final class SupervisorDemo {
    public static void main(String[] args) {
        boolean live = args.length > 0 && args[0].equals("--live");
        int start = args.length > 0 && (live || args[0].equals("--offline")) ? 1 : 0;
        String question =
                args.length > start
                        ? String.join(" ", Arrays.copyOfRange(args, start, args.length))
                        : "明天从杭州到上海玩一天，看看天气和火车票，规划行程";
        try (ModelHttpTransport transport = live ? new ModelHttpTransport() : null) {
            System.out.println(
                    live ? "模式：真实模型；天气和火车票仍为本地示例数据。" : "模式：离线脚本模型；真实执行 AgentScope 工具循环，数据仅供演示。");
            Supervisor supervisor =
                    live
                            ? new Supervisor(
                                    role -> liveModel(transport),
                                    Clock.systemUTC(),
                                    Limits.defaults(),
                                    TravelTools.demoData())
                            : Supervisor.offline(Clock.systemUTC());
            Result result =
                    supervisor
                            .run(new ChatInput(UUID.randomUUID().toString(), question, List.of()))
                            .block();
            if (result == null) throw new IllegalStateException("没有返回结果");
            result.events().forEach(System.out::println);
            System.out.println("执行状态：" + result.status());
            System.out.println("回答：\n" + result.answer());
        } finally {
            // 共享服务不要在单次请求结束时关闭全局传输；这里只处理命令行进程退出。
            HttpTransportFactory.shutdown();
        }
    }

    private static Model liveModel(ModelHttpTransport transport) {
        return OpenAIChatModel.builder()
                .apiKey(env("DEMO_API_KEY"))
                .baseUrl(env("DEMO_BASE_URL"))
                .modelName(env("DEMO_MODEL"))
                .httpTransport(transport)
                .stream(false)
                .build();
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("请配置环境变量 " + name);
        return value;
    }
}
