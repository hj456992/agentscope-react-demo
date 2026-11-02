package dev.garden.demo.supervisor.pojo.res;

/** 告警读取视图；阈值和实际值均为真实采集或配置值。 */
public record AlertRes(
        String alertId,
        String kind,
        String severity,
        String message,
        double metricValue,
        double thresholdValue,
        String status,
        long createdAt,
        long updatedAt) {}
