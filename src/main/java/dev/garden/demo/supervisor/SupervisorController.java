package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 六个 HTTP 接口统一使用可信身份；数据库操作调度到有界工作线程。
 *
 * <p>认证不在这里做：{@link SupervisorAuthFilter} 在请求进入 Controller 之前统一完成令牌校验，
 * 身份写入 Reactor Context，业务方法用 {@link RequestIdentity#require()} 读取。
 * 所以本类所有接口签名只包含业务参数；鉴权、请求头等全局或传输信息一律不进入方法入参。
 *
 * <p>POST 接口统一用一个命令对象承接本次请求的输入：{@link ChatCommand}
 * （body + {@code Prefer} 头）、{@link InstructionCommand}（全部在 body）。
 * 请求头到命令对象的合并由 {@link ChatCommandResolver} 负责；业务输入不再放路径变量。
 *
 * <p>WebFlux 的约定：控制器返回 {@code Mono/Flux}，框架负责订阅并把结果写回响应；
 * 方法体里只做参数校验和建链，不能 {@code block()}，否则会卡住事件线程。
 * 需要读写 JDBC 的入口（记忆接口）显式 {@code subscribeOn(boundedElastic)}，
 * 因为 H2 是阻塞调用，不能留在 Netty 的事件线程上。
 */
@RestController
@RequestMapping("/api/supervisor")
public class SupervisorController {
    private final TaskService tasks;
    private final ConversationService conversations;

    public SupervisorController(TaskService tasks, ConversationService conversations) {
        this.tasks = tasks;
        this.conversations = conversations;
    }

    /**
     * POST /chat 的输入：会话编号、问题、历史与请求编号，加上“是否异步”的偏好。
     *
     * <p>字段直接使用业务名称，不叫 {@code request} 之类的泛化名——
     * 每个接口的命令对象都应该能一眼看出它承载什么业务。
     */
    public record ChatCommand(ChatInput input, boolean async) {}

    /**
     * POST /instructions 的输入：任务编号和指令内容全部来自 JSON body。
     *
     * <p>不再把 runId 放在路径里——POST 接口只需要一个入参对象，调用方不用再关心路径拼接。
     */
    public record InstructionCommand(
            String runId,
            String instructionId,
            int expectedRevision,
            TaskService.Action action,
            String message) {
        TaskService.Instruction instruction() {
            return new TaskService.Instruction(
                    instructionId, expectedRevision, action, message);
        }
    }

    /**
     * POST /api/supervisor/chat：受理一次对话请求。
     *
     * <p>它<b>不只是建一个任务</b>，而是“建任务 + 立刻在后台启动执行 + 按模式返回”：
     *
     * <ol>
     *   <li><b>认证</b>：已由 {@link SupervisorAuthFilter} 在进入本方法之前完成——
     *       只认服务端配置的 Bearer 令牌（没有或错误就 401），身份决定后续所有任务和记忆的
     *       可见范围。身份不进接口参数，本方法用 {@link RequestIdentity#require()} 从上下文读取。
     *   <li><b>选模式</b>：默认同步等待；只有请求头带 {@code Prefer: respond-async}
     *       才走异步。请求头由 {@link ChatCommandResolver} 解析并写入命令对象。
     *   <li><b>建任务并启动</b>（{@link TaskService#submit}）：参数校验 →
     *       按 {@code requestId} 幂等查重（同编号同内容直接返回旧任务，内容不同 409）→
     *       同一会话已有 RUNNING 任务则 409 → 用户速率与容量检查 →
     *       写库 → 由服务端自己订阅执行链，任务开始在后台跑。
     *       这一段是阻塞操作，跑在有界弹性线程池上。
     *   <li><b>执行与 HTTP 解耦</b>：任务链的生命周期跟着任务行走，不跟着这次请求走；
     *       客户端断开只是“不再等”，任务不会被取消（要停必须显式发 CANCEL 指令）。
     *   <li><b>返回</b>：
     *       <ul>
     *         <li>异步：立即 202 + 当前快照（state 通常还是 RUNNING），
     *             之后用 GET /runs/{runId} 或指令接口推进；</li>
     *         <li>同步：长轮询到任务终态才返回——成功 200、FAILED 502、TIMEOUT 504。</li>
     *       </ul>
     * </ol>
     *
     * <p>方法体里没有 {@code block()}：返回的 Mono 由 WebFlux 订阅，结果由框架写回响应。
     */
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<TaskService.View>> chat(ChatCommand command) {
        // 第 1 步：身份从 Reactor Context 取；接口签名里没有鉴权参数。
        // 第 2 步：async 已由解析器从 Prefer 头填入命令对象，Controller 不直接读请求头。
        // 第 3 步：建任务并在后台启动执行；返回“最终视图”还是“当前快照”由 async 决定。
        return RequestIdentity.require()
                .flatMap(
                        identity ->
                                tasks.submit(
                                        identity, command.input(), command.async()))
                .map(
                        result -> {
                            // 第 4 步：只把业务状态翻译成 HTTP 状态码，业务状态仍保留在 body.state。
                            int code =
                                    command.async()
                                            ? 202
                                            : switch (result.state()) {
                                                case "FAILED" -> 502;
                                                case "TIMEOUT" -> 504;
                                                default -> 200;
                                            };
                            return ResponseEntity.status(code).body(result);
                        });
    }

    @GetMapping("/runs/{runId}")
    public Mono<TaskService.View> get(@PathVariable("runId") String runId) {
        return RequestIdentity.require().flatMap(identity -> tasks.get(identity, runId));
    }

    @PostMapping(value = "/instructions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<TaskService.InstructionReply>> instruct(
            @RequestBody InstructionCommand command) {
        return RequestIdentity.require()
                .flatMap(identity -> tasks.instruct(identity, command.runId(), command.instruction()))
                .map(
                        reply ->
                                ResponseEntity.status(
                                                command.action() == TaskService.Action.CANCEL
                                                                || command.action()
                                                                        == TaskService.Action.AUTO
                                                        ? 200
                                                        : 202)
                                        .body(reply));
    }

    @GetMapping("/memories")
    public Mono<ConversationService.Page> memories(
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        return RequestIdentity.require()
                .flatMap(
                        identity ->
                                Mono.fromCallable(
                                                () ->
                                                        conversations.memories(
                                                                identity, limit, cursor))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    @PutMapping(value = "/memories/{key}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ConversationService.Memory> put(
            @PathVariable("key") String memoryKey,
            @RequestBody ConversationService.MemoryInput memory) {
        return RequestIdentity.require()
                .flatMap(
                        identity ->
                                Mono.fromCallable(
                                                () ->
                                                        conversations.put(
                                                                identity, memoryKey, memory))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    @DeleteMapping("/memories/{key}")
    public Mono<ResponseEntity<Void>> delete(
            @PathVariable("key") String memoryKey,
            @RequestParam("expectedVersion") int expectedVersion) {
        return RequestIdentity.require()
                .flatMap(
                        identity ->
                                Mono.fromRunnable(
                                                () ->
                                                        conversations.delete(
                                                                identity,
                                                                memoryKey,
                                                                expectedVersion))
                                        .subscribeOn(Schedulers.boundedElastic()))
                .thenReturn(ResponseEntity.noContent().build());
    }
}
