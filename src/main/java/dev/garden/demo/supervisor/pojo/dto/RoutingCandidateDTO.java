package dev.garden.demo.supervisor.pojo.dto;

import java.util.List;

/**
 * 一个候选 Agent 的目录、权限和选择理由。
 *
 * <p>候选目录来自本地固定角色清单；是否可选来自当前身份权限；是否被选择来自本运行
 * 实际创建的子运行或计划任务，不由页面推断。
 */
public record RoutingCandidateDTO(
        String role,
        List<String> catalogTools,
        boolean permitted,
        boolean selected,
        String reason,
        String confidence) {}
