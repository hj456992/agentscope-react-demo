package dev.garden.demo.supervisor.pojo.dto;

import java.util.Map;

/** 这是实际查询记录，不依赖模型声称“已查询”。 */
public record EvidenceDTO(String tool, Status status, String source, Map<String, Object> data) {
    public EvidenceDTO {
        data = Map.copyOf(data);
    }
}
