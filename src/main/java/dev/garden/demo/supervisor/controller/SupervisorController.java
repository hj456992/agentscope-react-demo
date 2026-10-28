package dev.garden.demo.supervisor.controller;

import dev.garden.demo.supervisor.pojo.dto.Action;
import dev.garden.demo.supervisor.pojo.dto.RequestContextDTO;
import dev.garden.demo.supervisor.pojo.param.ChatParam;
import dev.garden.demo.supervisor.pojo.param.InstructionParam;
import dev.garden.demo.supervisor.pojo.req.MemoryReq;
import dev.garden.demo.supervisor.pojo.res.InstructionRes;
import dev.garden.demo.supervisor.pojo.res.MemoryPageRes;
import dev.garden.demo.supervisor.pojo.res.MemoryRes;
import dev.garden.demo.supervisor.pojo.res.RunRes;
import dev.garden.demo.supervisor.service.ConversationService;
import dev.garden.demo.supervisor.service.TaskService;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

/**
 * 接口方法只保留业务参数：身份与 Prefer 由请求 filter 写入 Reactor context。
 *
 * <p>POST 只有一个 xxxParam；GET 只有 @PathVariable；分页与版本一律走路径。
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
     * 提交一次对话任务，并按“同步 / 异步”两种约定返回结果。
     *
     * <p>同步调用（请求头不带 {@code Prefer: respond-async}）：一直等到任务结束才返回，
     * 状态码按最终状态映射为 200 / 502 / 504。
     *
     * <p>异步调用：任务入队后立刻返回 202 和当前任务快照，客户端随后用
     * {@code GET /runs/{runId}} 轮询进度。
     *
     * <p>本方法不阻塞线程：全程只组装 {@link Mono} 响应式链路，真正的耗时工作由
     * {@link TaskService#submit} 交给自己的调度器执行，请求线程组装完链路就归还。
     *
     * <p>注意“组装”的含义：方法返回时，除了创建几个 Mono 对象，业务代码一行都还没执行——
     * 连 {@code param.toRequest()} 和 {@link TaskService#submit} 都写在 flatMap 的回调里，
     * 要等 WebFlux 订阅这条链、订阅信号从链尾传到链头时才会被调用。
     *
     * <p>身份和异步标记由请求前置过滤器解析后写入 Reactor context，因此这里没有请求头参数；
     * 上下文缺失时视为未认证，返回 401。
     */
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<RunRes>> chat(@RequestBody ChatParam param) {
        // 读取 filter 放进 Reactor context 的请求上下文（身份 + 是否异步）。
        // 注意：context() 返回的是 Mono，此刻并没有真正读取，等订阅发生时才取值。
        return context()
                // flatMap：等上下文到达后，用它去发起下一段异步操作，
                // 并把下一段返回的 Mono “压平”成同一个流（而不是 Mono<Mono<...>>）。
                .flatMap(
                        ctx ->
                                // 真正的业务入口：把身份、请求体、异步标记交给 TaskService。
                                // 这一步只返回 Mono，不阻塞；建任务、跑 Agent 都发生在 TaskService 内部。
                                tasks.submit(ctx.identity(), param.toRequest(), ctx.async())
                                        // map：任务结果一到，就把它翻译成 HTTP 响应体；
                                        // 纯转换、不产生新的异步动作，所以用 map 而不是 flatMap。
                                        .map(
                                                result -> {
                                                    // 计算 HTTP 状态码。判断顺序是：异步优先，其次看任务最终状态。
                                                    int code =
                                                            // 异步模式：任务已经入队并开始执行，立刻回 202 Accepted
                                                            ctx.async()
                                                                    ? 202
                                                                    // 同步模式：按任务最终状态决定成功还是网关类错误码
                                                                    : switch (result.state()) {
                                                                        // 任务失败（模型或工具出错）→ 502 Bad Gateway
                                                                        case "FAILED" -> 502;
                                                                        // 任务超过绝对截止时间 → 504 Gateway Timeout
                                                                        case "TIMEOUT" -> 504;
                                                                        // 其余情况（例如 DONE）→ 200 成功
                                                                        default -> 200;
                                                                    };
                                                    // 用上面算好的状态码 + 任务快照组装响应；
                                                    // 这个值会顺着 Mono 交给 WebFlux，由它序列化成 JSON 写回客户端。
                                                    return ResponseEntity.status(code).body(result);
                                                }));
    }

    @GetMapping("/runs/{runId}")
    public Mono<RunRes> get(@PathVariable("runId") String runId) {
        return context().flatMap(ctx -> tasks.get(ctx.identity(), runId));
    }

    @PostMapping(value = "/instructions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<InstructionRes>> instruct(@RequestBody InstructionParam param) {
        return context()
                .flatMap(ctx -> tasks.instruct(ctx.identity(), param))
                .map(
                        reply ->
                                ResponseEntity.status(
                                                param.action() == Action.CANCEL
                                                                || param.action() == Action.AUTO
                                                        ? 200
                                                        : 202)
                                        .body(reply));
    }

    @GetMapping("/memories")
    public Mono<MemoryPageRes> memories() {
        return memoriesOf(null);
    }

    @GetMapping("/memories/{cursor}")
    public Mono<MemoryPageRes> memories(@PathVariable("cursor") String cursor) {
        return memoriesOf(cursor);
    }

    private Mono<MemoryPageRes> memoriesOf(String cursor) {
        return context()
                .flatMap(
                        ctx ->
                                Mono.fromCallable(
                                                () ->
                                                        conversations.memories(
                                                                ctx.identity(), 20, cursor))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    @PutMapping(value = "/memories/{key}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<MemoryRes> put(
            @PathVariable("key") String key, @RequestBody MemoryReq input) {
        return context()
                .flatMap(
                        ctx ->
                                Mono.fromCallable(
                                                () -> conversations.put(ctx.identity(), key, input))
                                        .subscribeOn(Schedulers.boundedElastic()));
    }

    @DeleteMapping("/memories/{key}/{expectedVersion}")
    public Mono<ResponseEntity<Void>> delete(
            @PathVariable("key") String key,
            @PathVariable("expectedVersion") int expectedVersion) {
        return context()
                .flatMap(
                        ctx ->
                                Mono.fromRunnable(
                                                () ->
                                                        conversations.delete(
                                                                ctx.identity(),
                                                                key,
                                                                expectedVersion))
                                        .subscribeOn(Schedulers.boundedElastic())
                                        .thenReturn(ResponseEntity.noContent().build()));
    }

    /**
     * 读取 filter 写在 Reactor context 里的请求上下文（“谁在请求” + “要不要异步”）。
     *
     * <p><b>为什么这个方法只能返回 Mono，不能直接返回对象？</b>
     * 因为 Controller 方法被调用时，请求还没有被订阅，此刻根本不存在“这一次的 context”。
     * context 不是全局变量、也不是 ThreadLocal，它只活在“一次订阅”里面。
     * 可以把它理解成“按订阅隔离的 ThreadLocal”：结构很像（一份独立小表 + key → 值），
     * 但隔离单位是订阅而不是线程；而且取值只能由订阅期算子把 context 递进来，
     * 不能像 ThreadLocal 那样在任意位置随手 get。
     * 也就是说，写方法体的那一刻，世界上还没有这份数据，任何直接读取都读不到。
     *
     * <p><b>那怎么才能读到？</b>必须让 Reactor 在订阅发生时把 context 主动递到我们手上。
     * {@link Mono#deferContextual} 就是专门递这个东西的算子，名字可以拆成两半看：
     * <ul>
     *   <li><b>defer（延迟）</b>：现在什么都不做，只登记一个回调；等有人来订阅时，Reactor 才调用它。</li>
     *   <li><b>Contextual（带上下文）</b>：调用这个回调时，Reactor 会把本次订阅的 context
     *       作为参数（{@code view}）传进来，我们就在回调里把需要的东西取出来。</li>
     * </ul>
     *
     * <p>所以本方法本身不做任何工作，它只是“预约”：预约在订阅那一刻去读 context。
     * 一次 HTTP 请求只会调用一次本方法、也只会读一次 context；读出身份之后就以普通参数
     * 往下传了，后续哪怕任务跑几分钟也不会再回来读。
     * 也因为 Mono 是冷流，每次订阅都会重新执行一次回调，读到的都是当次订阅自己的
     * context，不会串到别的请求上。
     *
     * <p><b>key 为什么是 {@code RequestContextDTO.class}？</b>因为 key 只是“同一份 context
     * 内部的栏目名”，不是跨请求的索引：所有请求都用同一个栏目名，但每份 context 各自独立。
     * 用户 A 的小票上只有用户 A 的身份，读不到时也不会“落到”用户 B 那份上——用户 B 的
     * 身份在另一份 context 里，两边互不可见。
     *
     * <p>约定：取不到上下文，说明请求没经过 {@code SupervisorRequestFilter}
     * （例如内部直调或测试），一律按未认证处理——发出 401 错误信号，由
     * {@link SupervisorErrorHandler} 统一转成 {@code {"code":"UNAUTHORIZED", ...}} 的 JSON。
     */
    private static Mono<RequestContextDTO> context() {
        // 这一行只是“登记回调”，返回的 Mono 此刻还没有任何数据；
        // lambda 要等 WebFlux 订阅时才会被 Reactor 调用。
        return Mono.deferContextual(
                // view：只读的 context 视图，由 Reactor 在订阅时填好递进来。
                // filter 里 contextWrite 写进去的 {身份, async} 就在这里面。
                // 注意它只是本次订阅的一份快照，出了这个 lambda 就没有意义了。
                view ->
                        // 用“类本身”当 key 查：filter 写入时存的正是 RequestContextDTO 这个类型。
                        // key 相当于“小票上的栏目名”，不是“用户编号”：所有请求都用同一个栏目名，
                        // 但每份 context 只属于它自己那次订阅，所以多个用户并发也不会串数据。
                        // 用 Class 当栏目名还有个好处：get 出来直接就是 RequestContextDTO，不用强转。
                        // context 不允许存 null，所以 hasKey 为 true 时，下面的 get 一定拿得到对象。
                        view.hasKey(RequestContextDTO.class)
                                // 命中：Mono.just 把现成的对象直接发射出去，
                                // 外层 flatMap 的回调就会收到这个请求上下文对象（身份和异步标记）。
                                ? Mono.just(view.get(RequestContextDTO.class))
                                // 没命中：发出“错误信号”（不是 throw），让链路走 onError 分支，
                                // 最终由全局异常处理器统一变成 401 JSON，各接口不必各写一套。
                                : Mono.error(
                                        // 这里只表达“未认证”这个语义，具体错误码和中文文案
                                        // 由 SupervisorErrorHandler 负责，避免重复。
                                        new ResponseStatusException(
                                                HttpStatus.UNAUTHORIZED,
                                                "请提供有效身份令牌。")));
    }

}
