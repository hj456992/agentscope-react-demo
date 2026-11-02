package dev.garden.demo.supervisor.controller;

import dev.garden.demo.supervisor.pojo.dto.ApprovalDTO;
import dev.garden.demo.supervisor.pojo.dto.RequestContextDTO;
import dev.garden.demo.supervisor.pojo.param.AgentReleaseParam;
import dev.garden.demo.supervisor.pojo.param.AgentRollbackParam;
import dev.garden.demo.supervisor.pojo.param.ApprovalParam;
import dev.garden.demo.supervisor.pojo.param.PromptParam;
import dev.garden.demo.supervisor.pojo.param.PromptReleaseParam;
import dev.garden.demo.supervisor.pojo.req.PromptUpdateReq;
import dev.garden.demo.supervisor.pojo.res.AgentCatalogRes;
import dev.garden.demo.supervisor.pojo.res.AgentRes;
import dev.garden.demo.supervisor.pojo.res.AlertRes;
import dev.garden.demo.supervisor.pojo.res.AuditRes;
import dev.garden.demo.supervisor.pojo.res.MetricsRes;
import dev.garden.demo.supervisor.pojo.res.OperationRes;
import dev.garden.demo.supervisor.pojo.res.PromptReleaseRes;
import dev.garden.demo.supervisor.pojo.res.PromptRes;
import dev.garden.demo.supervisor.pojo.res.ReleaseRes;
import dev.garden.demo.supervisor.service.AgentCatalogService;
import dev.garden.demo.supervisor.service.ApprovalService;
import dev.garden.demo.supervisor.service.MetricsService;
import dev.garden.demo.supervisor.service.PromptRegistryService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 平台治理读取与操作入口。
 *
 * <p>所有接口沿用现有鉴权过滤器与错误处理：身份来自 Reactor context，令牌不进入
 * 控制器参数。GET 只使用路径参数；POST 只有一个 Param 入参；PUT 使用路径和请求体。
 * 提示词正文、清单签名密钥和下游凭据不进入任何响应。
 */
@RestController
@RequestMapping("/api/supervisor")
public class GovernanceController {
    private final PromptRegistryService prompts;
    private final AgentCatalogService agents;
    private final ApprovalService approvals;
    private final MetricsService metrics;

    /** 唯一构造器：治理服务全部由容器注入。 */
    public GovernanceController(
            PromptRegistryService prompts,
            AgentCatalogService agents,
            ApprovalService approvals,
            MetricsService metrics) {
        this.prompts = prompts;
        this.agents = agents;
        this.approvals = approvals;
        this.metrics = metrics;
    }

    @GetMapping("/prompts")
    public Mono<List<PromptRes>> prompts() {
        return read(prompts::list);
    }

    @GetMapping("/prompts/releases")
    public Mono<List<PromptReleaseRes>> promptReleases() {
        return read(prompts::releases);
    }

    @GetMapping("/prompts/{promptId}/{version}")
    public Mono<PromptRes> prompt(
            @PathVariable("promptId") String promptId, @PathVariable("version") String version) {
        return read(() -> prompts.get(promptId, version));
    }

    @PostMapping(value = "/prompts", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<PromptRes> createPrompt(@RequestBody PromptParam param) {
        return read(() -> prompts.create(param));
    }

    @PutMapping(
            value = "/prompts/{promptId}/{version}",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<PromptRes> updatePrompt(
            @PathVariable("promptId") String promptId,
            @PathVariable("version") String version,
            @RequestBody PromptUpdateReq req) {
        return read(() -> prompts.update(promptId, version, req));
    }

    @PostMapping(value = "/prompts/releases", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Void> releasePrompt(@RequestBody PromptReleaseParam param) {
        return Mono.fromRunnable(() -> prompts.release(param))
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    @GetMapping("/agents")
    public Mono<List<AgentRes>> agents() {
        return read(agents::agents);
    }

    @GetMapping("/agents/catalog")
    public Mono<AgentCatalogRes> catalog() {
        return context()
                .flatMap(
                        ctx ->
                                Mono.fromCallable(() -> agents.catalog(ctx.identity()))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/agents/{agentId}/versions/{version}")
    public Mono<AgentRes> agent(
            @PathVariable("agentId") String agentId, @PathVariable("version") String version) {
        return read(() -> agents.agent(agentId, version));
    }

    @GetMapping("/agents/{agentId}/releases")
    public Mono<List<ReleaseRes>> releases(@PathVariable("agentId") String agentId) {
        return read(() -> agents.releases(agentId));
    }

    @PostMapping(value = "/agents/releases", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ReleaseRes> release(@RequestBody AgentReleaseParam param) {
        return read(() -> agents.release(param));
    }

    @PostMapping(value = "/agents/rollbacks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ReleaseRes> rollback(@RequestBody AgentRollbackParam param) {
        return read(() -> agents.rollback(param));
    }

    @GetMapping("/registry/audit")
    public Mono<List<AuditRes>> audit() {
        return read(() -> agents.auditLog(200));
    }

    @GetMapping("/metrics")
    public Mono<MetricsRes> metrics() {
        return read(metrics::collect);
    }

    @GetMapping("/alerts")
    public Mono<List<AlertRes>> alerts() {
        return read(() -> metrics.collect().alerts());
    }

    @GetMapping("/alerts/{alertId}")
    public Mono<AlertRes> alert(@PathVariable("alertId") String alertId) {
        return read(
                () ->
                        metrics.collect().alerts().stream()
                                .filter(value -> value.alertId().equals(alertId))
                                .findFirst()
                                .orElseThrow(
                                        () -> new ResponseStatusException(HttpStatus.NOT_FOUND)));
    }

    @GetMapping("/approvals/{approvalId}")
    public Mono<ApprovalDTO> approval(@PathVariable("approvalId") String approvalId) {
        return context()
                .flatMap(
                        ctx ->
                                Mono.fromCallable(
                                                () -> approvals.get(ctx.identity(), approvalId))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping(value = "/approvals", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ApprovalDTO> decide(@RequestBody ApprovalParam param) {
        return context()
                .flatMap(
                        ctx ->
                                Mono.fromCallable(
                                                () -> approvals.decide(ctx.identity(), param))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/operations/{tool}/{operationId}")
    public Mono<OperationRes> operation(
            @PathVariable("tool") String tool,
            @PathVariable("operationId") String operationId) {
        return context()
                .flatMap(
                        ctx ->
                                Mono.fromCallable(
                                                () ->
                                                        approvals.operation(
                                                                ctx.identity(), tool, operationId))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private static <T> Mono<T> read(java.util.concurrent.Callable<T> action) {
        return Mono.fromCallable(action).subscribeOn(Schedulers.boundedElastic());
    }

    private static Mono<RequestContextDTO> context() {
        return Mono.deferContextual(
                view ->
                        view.hasKey(RequestContextDTO.class)
                                ? Mono.just(view.get(RequestContextDTO.class))
                                : Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED, "请提供有效身份令牌。")));
    }
}
