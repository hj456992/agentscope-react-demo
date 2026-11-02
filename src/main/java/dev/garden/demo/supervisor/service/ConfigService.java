package dev.garden.demo.supervisor.service;

import dev.garden.demo.supervisor.SupervisorPolicy;
import dev.garden.demo.supervisor.config.SupervisorSettings;
import dev.garden.demo.supervisor.pojo.dto.IdentityDTO;
import dev.garden.demo.supervisor.pojo.res.ConfigRes;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 生成当前实例的脱敏配置证据。
 *
 * <p>只读取启动时绑定的配置对象，不访问模型或数据库；令牌值、密钥和连接串账号
 * 永不进入响应。认证身份只保留租户、用户和工具权限，供验收核对身份矩阵。
 */
@Service
public final class ConfigService {
    private final SupervisorSettings settings;
    private final SupervisorPolicy policy;
    private final AccessPolicy access;

    /**
     * 唯一构造器：全部依赖由容器注入。
     *
     * <p>配置对象不可变，服务不缓存派生结果，确保页面读取到的是当前实例实际绑定值。
     */
    public ConfigService(
            SupervisorSettings settings, SupervisorPolicy policy, AccessPolicy access) {
        this.settings = settings;
        this.policy = policy;
        this.access = access;
    }

    /** 返回脱敏后的配置快照。 */
    public ConfigRes config() {
        List<String> identities = new ArrayList<>();
        for (IdentityDTO identity : access.identities()) {
            Set<String> permissions = new TreeSet<>(identity.permissions());
            identities.add(
                    identity.tenantId()
                            + ":"
                            + identity.userId()
                            + ":"
                            + String.join(",", permissions));
        }
        return new ConfigRes(
                settings.mode(),
                settings.timeout().toSeconds(),
                settings.maxModelCalls(),
                settings.maxOutputTokens(),
                policy.execution().maxSteps(),
                policy.execution().contextInputLimit(),
                settings.maxConcurrentRequests(),
                settings.maxConcurrentPerUser(),
                settings.tasksPerUserMinute(),
                settings.modelCallsPerMinute(),
                settings.modelTokensPerMinute(),
                settings.maxStoredTasks(),
                settings.taskRetention().toSeconds(),
                settings.conversationRetention().toSeconds(),
                settings.preferenceRetention().toSeconds(),
                settings.databaseUrl().startsWith("jdbc:postgresql:")
                        ? "postgresql"
                        : settings.databaseUrl().startsWith("jdbc:h2:") ? "h2" : "unknown",
                List.copyOf(identities),
                policy.business().enabled(),
                policy.business().rules().size(),
                policy.factCheck().enabled(),
                policy.factCheck().semanticVerifierEnabled(),
                policy.correction().enabled(),
                policy.multiAgent().mode().name(),
                policy.multiAgent().plannerEnabled(),
                policy.multiAgent().maxAgents(),
                policy.multiAgent().maxDepth(),
                policy.topic().enabled(),
                policy.summary().alertEnabled(),
                policy.summary().fidelityThreshold(),
                policy.summary().missingConditionPolicy().name(),
                policy.summary().recallEnabled(),
                policy.execution().enabled(),
                policy.loop().enabled(),
                policy.loop().semanticEnabled(),
                policy.loop().maxRepeatedToolCalls(),
                settings.acceptanceHintsEnabled(),
                settings.downstreamUrl() != null && !settings.downstreamUrl().isBlank(),
                settings.downstreamTimeout().toSeconds(),
                settings.toolDelayMs());
    }
}
