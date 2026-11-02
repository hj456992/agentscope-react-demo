package dev.garden.demo.supervisor.support;

import dev.garden.demo.supervisor.pojo.dto.SideEffectOutcomeDTO;
import dev.garden.demo.supervisor.service.SideEffectLedger;

import java.util.Map;

/**
 * 未配置本地下游时的显式占位适配器。
 *
 * <p>Spring 不允许容器注入 null Bean；返回本适配器可保持审批接口可读。{@link #available()}
 * 明确为 false，审批服务据此拒绝执行并返回“未配置本地下单下游”，不会伪造成功。若调用方
 * 绕过可用性检查，submit 和 lookup 直接抛出异常，避免静默写入。
 */
public final class UnavailableSideEffectDownstream implements SideEffectLedger.Downstream {
    @Override
    public boolean available() {
        return false;
    }

    @Override
    public boolean supportsIdempotency() {
        return false;
    }

    @Override
    public SideEffectOutcomeDTO submit(String operationId, Map<String, Object> parameters) {
        throw new IllegalStateException("本实例未配置本地下单下游");
    }

    @Override
    public SideEffectOutcomeDTO lookup(String operationId) {
        throw new IllegalStateException("本实例未配置本地下单下游");
    }
}
