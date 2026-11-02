package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import dev.garden.demo.supervisor.pojo.dto.*;
import dev.garden.demo.supervisor.support.LiveModelFactory;

import io.agentscope.core.model.transport.HttpTransportFactory;

import java.time.Clock;
import java.util.*;

/** 命令行入口。默认真实模型；仅显式 --offline 或 SUPERVISOR_MODE=offline 使用离线脚本。 */
public final class SupervisorDemo {
    public static void main(String[] args) {
        boolean explicitMode =
                args.length > 0 && (args[0].equals("--live") || args[0].equals("--offline"));
        String mode = explicitMode ? args[0].substring(2) : System.getenv("SUPERVISOR_MODE");
        if (mode == null || mode.isBlank()) {
            mode = "live";
        }
        if (!mode.equals("live") && !mode.equals("offline")) {
            throw new IllegalArgumentException("SUPERVISOR_MODE 只能是 live 或 offline");
        }
        boolean live = mode.equals("live");
        int start = explicitMode ? 1 : 0;
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
                                    LiveModelFactory.create(System::getenv, transport),
                                    Clock.systemUTC(),
                                    LimitsDTO.defaults(),
                                    TravelTools.demoData())
                            : Supervisor.offline(Clock.systemUTC());
            ResultDTO result =
                    supervisor
                            .run(new RequestDTO(UUID.randomUUID().toString(), question, List.of()))
                            .block();
            if (result == null) {
                throw new IllegalStateException("没有返回结果");
            }
            result.events().forEach(System.out::println);
            System.out.println("执行状态：" + result.status());
            System.out.println("回答：\n" + result.answer());
        } finally {
            // 共享服务不要在单次请求结束时关闭全局传输；这里只处理命令行进程退出。
            HttpTransportFactory.shutdown();
        }
    }
}
