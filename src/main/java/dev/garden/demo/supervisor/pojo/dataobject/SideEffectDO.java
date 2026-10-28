package dev.garden.demo.supervisor.pojo.dataobject;

/** side_effect_operations 表的行映射。 */
public final class SideEffectDO {
    public String tenantId;
    public String userId;
    public String toolName;
    public String operationId;
    public String downstreamId;
    public String paramsHash;
    public String paramsJson;
    public String operationStatus;
    public String responseText;
    public int submitCount;
    public int lookupCount;
    public long nextLookup;
    public boolean retryAllowed;
}
