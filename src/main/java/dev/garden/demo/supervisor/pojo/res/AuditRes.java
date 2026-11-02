package dev.garden.demo.supervisor.pojo.res;

/** 注册表审计记录；不含签名密钥或清单全文。 */
public record AuditRes(
        long auditId,
        String action,
        String subject,
        String outcome,
        String detail,
        String actor,
        long createdAt) {}
