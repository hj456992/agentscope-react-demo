package dev.garden.demo.supervisor.pojo.dto;

/**
 * 本地向量检索命中。
 *
 * <p>{@code embedding} 明确标注本地哈希嵌入，不宣称生产向量库或语义模型能力。
 */
public record MemoryVectorHitDTO(
        String key,
        String value,
        int version,
        String source,
        double score,
        String embedding,
        String dataClass) {}
