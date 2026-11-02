package dev.garden.demo.supervisor.pojo.dataobject;

/** supervisor_alerts 表的行映射；数值来自实际采集，不用人工汇总覆盖。 */
public final class AlertDO {
    public String alertId;
    public String kind;
    public String severity;
    public String message;
    public double metricValue;
    public double thresholdValue;
    public String status;
    public long createdAt;
    public long updatedAt;
}
