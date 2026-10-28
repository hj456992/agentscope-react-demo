package dev.garden.demo.supervisor.pojo.res;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import dev.garden.demo.supervisor.pojo.dto.ResultDTO;

/** 任务查询与提交的响应；结果字段保持原先的平铺结构。 */
public record RunRes(
        @JsonUnwrapped ResultDTO result,
        String runId,
        int revision,
        String requestId,
        String state,
        int modelCalls,
        long deadline,
        String replacedBy) {}
