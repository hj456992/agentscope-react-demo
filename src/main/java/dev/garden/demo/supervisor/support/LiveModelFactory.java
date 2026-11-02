package dev.garden.demo.supervisor.support;

import dev.garden.demo.supervisor.ModelHttpTransport;

import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.openai.OpenAIChatModel;

import java.net.URI;
import java.util.Map;
import java.util.function.Function;

/** 服务和命令行共用的真实模型装配；只读取运行环境，不保存或输出凭据。 */
public final class LiveModelFactory {
    private LiveModelFactory() {}

    /** 启动时核对配置，每个角色使用独立模型实例；配置缺失绝不回退离线脚本。 */
    public static Function<String, Model> create(
            Function<String, String> environment, ModelHttpTransport transport) {
        String apiKey = value(environment, "DEMO_API_KEY", "DEEPSEEK_API_KEY", "");
        if (apiKey.isBlank()) {
            throw new IllegalArgumentException(
                    "真实模型模式缺少凭据：请配置 DEMO_API_KEY 或 DEEPSEEK_API_KEY");
        }
        String baseUrl =
                value(environment, "DEMO_BASE_URL", "DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        String modelName = value(environment, "DEMO_MODEL", "DEEPSEEK_MODEL", "deepseek-flash");
        GenerateOptions.Builder options = GenerateOptions.builder();
        if ("api.deepseek.com".equalsIgnoreCase(URI.create(baseUrl).getHost())) {
            // 现有循环回传标准工具消息；关闭供应商默认思考，避免要求额外回传 reasoning_content。
            options.additionalBodyParam("thinking", Map.of("type", "disabled"));
        }
        GenerateOptions defaults = options.build();
        return role ->
                OpenAIChatModel.builder()
                        .apiKey(apiKey)
                        .baseUrl(baseUrl)
                        .modelName(modelName)
                        .generateOptions(defaults)
                        .stream(false)
                        .httpTransport(transport)
                        .build();
    }

    private static String value(
            Function<String, String> environment, String primary, String secondary, String fallback) {
        String value = environment.apply(primary);
        if (value != null && !value.isBlank()) {
            return value;
        }
        value = environment.apply(secondary);
        if (value != null && !value.isBlank()) {
            return value;
        }
        return fallback;
    }
}
