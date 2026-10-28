package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 六个 HTTP 接口统一使用可信身份；数据库操作调度到有界工作线程。
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
    private final AccessPolicy access;

    public SupervisorController(
            TaskService tasks, ConversationService conversations, AccessPolicy access) {
        this.tasks = tasks;
        this.conversations = conversations;
        this.access = access;
    }

    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<TaskService.View>> chat(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "Prefer", required = false) String prefer,
            @RequestBody Request request) {
        var identity = access.authenticate(auth);
        boolean async = prefersAsync(prefer);
        return tasks.submit(identity, request, async)
                .map(
                        result -> {
                            int code =
                                    async
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
    public Mono<TaskService.View> get(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable("runId") String id) {
        return tasks.get(access.authenticate(auth), id);
    }

    @PostMapping(value = "/runs/{runId}/instructions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<TaskService.InstructionReply>> instruct(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable("runId") String id,
            @RequestBody TaskService.Instruction instruction) {
        return tasks.instruct(access.authenticate(auth), id, instruction)
                .map(
                        reply ->
                                ResponseEntity.status(
                                                instruction.action() == TaskService.Action.CANCEL
                                                                || instruction.action()
                                                                        == TaskService.Action.AUTO
                                                        ? 200
                                                        : 202)
                                        .body(reply));
    }

    @GetMapping("/memories")
    public Mono<ConversationService.Page> memories(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "cursor", required = false) String cursor) {
        var identity = access.authenticate(auth);
        return Mono.fromCallable(() -> conversations.memories(identity, limit, cursor))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping(value = "/memories/{key}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ConversationService.Memory> put(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable("key") String key,
            @RequestBody ConversationService.MemoryInput input) {
        var identity = access.authenticate(auth);
        return Mono.fromCallable(() -> conversations.put(identity, key, input))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/memories/{key}")
    public Mono<ResponseEntity<Void>> delete(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @PathVariable("key") String key,
            @RequestParam("expectedVersion") int expectedVersion) {
        var identity = access.authenticate(auth);
        return Mono.fromRunnable(() -> conversations.delete(identity, key, expectedVersion))
                .subscribeOn(Schedulers.boundedElastic())
                .thenReturn(ResponseEntity.noContent().build());
    }

    /** Prefer 是按逗号分隔的偏好列表，参数用分号带；respond-async 出现在任意项即异步。 */
    private static boolean prefersAsync(String prefer) {
        if (prefer == null) return false;
        for (String item : prefer.split(","))
            if ("respond-async".equalsIgnoreCase(item.split(";")[0].trim())) return true;
        return false;
    }
}
