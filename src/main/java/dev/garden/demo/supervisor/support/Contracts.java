package dev.garden.demo.supervisor.support;

import io.agentscope.core.util.JsonUtils;

/** 跨包共用的 JSON 与字符串工具，不承载业务状态。 */
public final class Contracts {
    private Contracts() {}

    public static String json(Object value) {
        return JsonUtils.getJsonCodec().toJson(value);
    }

    public static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
