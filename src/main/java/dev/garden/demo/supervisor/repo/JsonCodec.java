package dev.garden.demo.supervisor.repo;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 仓储层统一的 JSON 编解码器，保留 Spring 注入的模块与 H2 CLOB 字符串形态。 */
public final class JsonCodec {
    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper base) {
        this.mapper = base.copy().findAndRegisterModules();
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("记录无法序列化", error);
        }
    }

    public <T> T decode(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception error) {
            throw new IllegalStateException("记录损坏", error);
        }
    }
}
