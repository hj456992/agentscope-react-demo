package dev.garden.demo.supervisor.pojo.dto;

/** 业务意图只用于结果展示和规则校验，不参与是否执行工具的决策。 */
public enum Intent {
    WEATHER,
    TRAIN,
    ITINERARY,
    UNKNOWN
}
