package dev.garden.demo.supervisor.config;

/**
 * 一条业务规则的扁平配置。
 *
 * <p>{@code tools} 使用逗号分隔；{@code type} 对应
 * {@link dev.garden.demo.supervisor.SupervisorPolicy.BusinessRule.Type}。
 * 该类只负责配置绑定，规则执行由 {@code BusinessRules} 完成。
 */
public record BusinessRuleSetting(
        String id,
        String tools,
        String type,
        String field,
        String otherField,
        String suggestion) {}
