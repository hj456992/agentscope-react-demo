package dev.garden.demo.supervisor.pojo.dto;

import java.util.List;

/** 原样保留各条用户输入边界和已消费版本。 */
public record AgentInputDrainDTO(List<String> inputs, int consumedRevision, long deliveryId) {}
