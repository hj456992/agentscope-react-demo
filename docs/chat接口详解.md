# chat 接口详解

> 本文说明 `POST /api/supervisor/chat` 从请求进来到响应写回的完整链路，面向联调与代码阅读。
> 只描述现状，不改变任何行为约束；接口约束仍以 `CLAUDE.md` 与 `docs/Supervisor使用说明.md` 为准。
>
> 配套阅读：
>
> - `docs/如何理解Mono.md`：Mono 是什么、为什么“返回 Mono”不等于“异步返回”；
> - `docs/Reactor上下文说明.md`：Reactor context 是什么、为什么必须用 `deferContextual` 读。

## 一、一句话概括

提交一次旅行规划任务。

- **不带** `Prefer: respond-async`：一直等到任务结束，直接返回最终结果（同步模式）；
- **带** `Prefer: respond-async`：任务入队后立刻返回 `202` + 任务号，客户端自己去查进度（异步模式）。

两种模式**都只调用一次** `chat()`，区别只在于返回的 `Mono` 等多久才兑现。

## 二、接口契约

| 项 | 内容 |
|---|---|
| 方法与路径 | `POST /api/supervisor/chat` |
| `Content-Type` | 必须是 `application/json`，否则 415 |
| `Authorization` | 必须，形如 `Bearer <token>`；缺失或无效由 filter 直接返回 401 |
| `Prefer` | 可选；值为 `respond-async` 时走异步模式 |
| 请求体 | 唯一参数 `ChatParam` |
| 响应体 | `RunRes` |

请求头全部由 `filter/SupervisorRequestFilter` 处理，`chat()` 方法本身不接收任何请求头参数——这是 `CLAUDE.md` 第三节的硬性要求。

## 三、请求体 `ChatParam`

```java
public record ChatParam(
        String sessionId, String question, List<TurnDTO> history, String requestId) {}
```

| 字段 | 必填 | 约束 | 说明 |
|---|---|---|---|
| `sessionId` | 是 | 非空，≤ 200 字符 | 会话编号。同一会话已有 `RUNNING` 任务时再提交会 409 |
| `question` | 是 | 非空，≤ 2000 字符 | 本轮问题 |
| `history` | 否 | 每项 `question` 非空、`answer` 非 null | 调用方自报的历史，**只作为待核实资料**；服务端以自己的持久化历史为准 |
| `requestId` | 否 | ≤ 200 字符 | 幂等编号；省略时服务端生成 UUID |

校验在 `service/TaskStoreService.java:451` 的 `validateRequest`，任何一条不满足统一返回 400。

请求示例：

```bash
curl 'http://127.0.0.1:8080/api/supervisor/chat' \
  -H 'Authorization: Bearer demo-token' \
  -H 'Content-Type: application/json' \
  -H 'Prefer: respond-async' \
  -d '{
        "requestId": "request-1",
        "sessionId": "session-1",
        "question": "明天从杭州到上海玩一天，看看天气和火车票，规划行程",
        "history": []
      }'
```

## 四、响应体 `RunRes`

```java
public record RunRes(
        @JsonUnwrapped ResultDTO result,
        String runId,
        int revision,
        String requestId,
        String state,
        int modelCalls,
        long deadline,
        String replacedBy) {}
```

`@JsonUnwrapped` 表示 `ResultDTO` 的字段**平铺**到 JSON 顶层，不额外套一层 `result`。

| 字段 | 说明 |
|---|---|
| `runId` | 任务号，后续 `GET /runs/{runId}` 和指令接口都用它 |
| `state` | 任务状态：`RUNNING` / `DONE` / `FAILED` / `TIMEOUT` / `NEEDS_INPUT` / `CANCELLED` / `REPLACED` |
| `revision` | 任务版本，提交指令（`/instructions`）时要带上期望值 |
| `modelCalls` | 已消耗的模型调用次数 |
| `deadline` | 绝对截止时间（epoch 毫秒），默认创建后 60 秒 |
| `replacedBy` | 被哪个新任务取代（仅 `REPLACED` 时有值） |
| `status` / `answer` / `intent` / `evidence` / `events` … | 平铺自 `ResultDTO` 的结果字段 |

异步模式下的典型响应（`202`，任务刚开始跑）：

```json
{
  "status": "RUNNING",
  "originalQuery": "明天从杭州到上海玩一天……",
  "answer": "任务执行中",
  "runId": "8f1c…",
  "revision": 0,
  "requestId": "request-1",
  "state": "RUNNING",
  "modelCalls": 0,
  "deadline": 1761710400000,
  "replacedBy": null
}
```

## 五、完整链路

```text
HTTP 请求到达
   │
   ├─① SupervisorRequestFilter                        filter/SupervisorRequestFilter.java:39
   │     ├─ 解析 Authorization → 失败直接写 401 JSON，不进 Controller
   │     ├─ 解析 Prefer: respond-async
   │     └─ contextWrite 把 RequestContextDTO(身份, async) 写入 Reactor context
   │
   ├─② SupervisorController.chat                      controller/SupervisorController.java:63
   │     方法体只组装 Mono 链，立刻返回；此刻业务代码一行都没执行
   │
   ├─③ Spring WebFlux 订阅这个 Mono
   │     订阅信号自下而上传播 → deferContextual 回调被调用 → 读出身份与 async
   │
   ├─④ TaskService.submit                             service/TaskService.java:91
   │     Mono.fromCallable(create).subscribeOn(调度器)
   │     └─ create()：幂等查重 → 会话互斥检查 → 容量检查 → 落库
   │        若任务为 RUNNING 且未被持有 → start() 另起一条独立订阅执行任务
   │
   └─⑤ 按 async 分叉
         异步 → Mono.just(当前快照)         → 立刻兑现 → 202
         同步 → await() 每 25ms 查一次状态  → 任务结束后兑现 → 200 / 502 / 504
```

### ① filter：进入业务代码之前

```java
// filter/SupervisorRequestFilter.java:53-55
RequestContextDTO context = new RequestContextDTO(identity, async);
return chain.filter(exchange)
        .contextWrite(view -> view.put(RequestContextDTO.class, context));
```

身份没解析出来时，filter 直接写出 401 的 `ErrorRes` JSON 并结束，Controller 不会被调用。

`RequestContextDTO` 这个 key 是“小票上的栏目名”，不是用户编号；每份 context 只属于自己那次订阅，所以并发多个用户不会串数据（详见 `Reactor上下文说明.md`）。

### ② Controller：只组装，不执行

```java
// controller/SupervisorController.java:63
public Mono<ResponseEntity<RunRes>> chat(@RequestBody ChatParam param) {
    return context()
            .flatMap(ctx -> tasks.submit(ctx.identity(), param.toRequest(), ctx.async())
                    .map(result -> ResponseEntity.status(code).body(result)));
}
```

方法返回时，`context()`、`submit()` 里面都还没跑——它交出去的只是一张“菜谱”。

### ③ 订阅：context 在这一刻才被读到

WebFlux 对返回的 Mono 调用 `subscribe()`，订阅信号自下而上走：

1. 经过 filter 的 `contextWrite`：把小票 `{身份, async}` 装进 Context；
2. 到达 `context()` 里的 `Mono.deferContextual`：Reactor 调用回调，把 `ContextView` 作为 `view` 传进来；
3. `view.hasKey(RequestContextDTO.class)` 命中 → `Mono.just(ctx)` 发出身份；
4. 外层 `flatMap` 收到 `ctx`，调用 `tasks.submit(...)`。

**一次 HTTP 请求只读一次 context**；读出身份后，后续全以普通方法参数传递，任务跑多久都不会再回来读。

### ④ `TaskService.submit`：真正开始干活

```java
// service/TaskService.java:91-99
public Mono<RunRes> submit(IdentityDTO identity, RequestDTO request, boolean async) {
    return Mono.fromCallable(() -> create(identity, request))
            .subscribeOn(scheduler)
            .flatMap(task -> async
                    ? Mono.just(store.view(identity, task))
                    : await(identity, task.runId));
}
```

- `fromCallable` + `subscribeOn(scheduler)`：建任务的同步代码在 `supervisor-runtime` 调度器上跑，不占用 WebFlux 的请求线程；
- `create()` 里完成四件事：`requestId` 幂等查重、同会话 `RUNNING` 互斥、并发与配额检查、任务落库；任务状态为 `RUNNING` 且尚无执行订阅时，调用 `start()`；
- `start()`（`TaskService.java:151`）用**另一条独立订阅**跑任务，并用 `doFinally` 在结束时释放持有关系——这是“异步模式下任务能活过 HTTP 请求”的原因。

### ⑤ 分叉：同步与异步

| 模式 | 分支 | 兑现时机 |
|---|---|---|
| 异步 | `Mono.just(store.view(...))` | 任务刚创建完就兑现，返回当前快照 |
| 同步 | `await(identity, runId)` | 每 25ms 查一次状态，直到不再是 `RUNNING` |

同步等待的实现在 `TaskService.java:112-120`：

```java
private Mono<RunRes> await(IdentityDTO identity, String id) {
    return get(identity, id)
            .flatMap(view -> view.state().equals("RUNNING")
                    ? Mono.delay(Duration.ofMillis(25))
                            .then(Mono.defer(() -> await(identity, id)))
                    : Mono.just(view));
}
```

注意：这里重复订阅的只是 `get()` 这个小 Mono，**不是**重复调用 `chat()`，也不会重新读 context。

## 六、`chat()` 逐段拆解

| 代码 | 作用 | 关键点 |
|---|---|---|
| `context()` | 取 filter 写入的身份 + async | 返回 Mono，此刻未读；订阅时才读 |
| `.flatMap(ctx -> …)` | 拿到 ctx 后再发起下一步 | 下一步返回的也是 Mono，用 `flatMap` 摊平；用 `map` 会得到 `Mono<Mono<…>>` |
| `tasks.submit(...)` | 交给 service 建任务、跑任务 | 只返回 Mono，不阻塞请求线程 |
| `.map(result -> …)` | 把任务结果翻译成 HTTP 响应 | 纯转换，不产生新的异步动作，所以用 `map` |
| `ResponseEntity.status(code).body(result)` | 组装状态码 + 响应体 | 这一步的结果由 WebFlux 序列化成 JSON |

状态码的判断顺序：**先看是否异步，再看任务最终状态**。

```java
int code = ctx.async()
        ? 202
        : switch (result.state()) {
            case "FAILED" -> 502;
            case "TIMEOUT" -> 504;
            default -> 200;
        };
```

### 6.1 组装阶段：方法返回前，业务代码一行都没跑

请求命中 `chat()` 之后，方法体执行完只做了一件事：**创建几个 Mono 对象、把它们串起来、return**。

| 代码 | 组装阶段（方法返回前） | 订阅之后 |
|---|---|---|
| `context()` | ✅ 执行了，但只是创建一个 Mono 对象 | —— |
| `.flatMap(...)` | ✅ 执行了，只是挂上一个回调 | ✅ 才调用回调 |
| `ctx -> tasks.submit(...)` | ❌ 没跑 | ✅ 订阅后跑 |
| `param.toRequest()` | ❌ 没跑（写在回调里） | ✅ 订阅后跑 |
| `tasks.submit(...)` | ❌ 没跑 | ✅ 订阅后跑 |
| `.map(result -> ...)` | ✅ 挂上回调 | ✅ 才调用回调 |
| `switch (result.state())` | ❌ 没跑 | ✅ 任务结束后跑 |
| `ResponseEntity.status(code).body(result)` | ❌ 没跑 | ✅ 跑 |

等价示意：

```java
Mono<RequestContextDTO> a = new DeferContextualMono(...);       // 只存了回调，没执行
Mono<ResponseEntity<RunRes>> b = new FlatMapMono(a, lambda1);  // 只存了回调，没执行
Mono<ResponseEntity<RunRes>> c = new MapMono(b, lambda2);      // 只存了回调，没执行
return c;
```

**没建任务、没查库、没调模型、没读 context、连请求体都还没转换成 `RequestDTO`。**

唯一会提前执行的，是写在 lambda 外面的表达式（例如 `Duration.ofMillis(25)`、`Schedulers.boundedElastic()`），本项目里它们都不产生业务副作用。

> 一句话：`chat()` 返回的是**说明书**，不是**做好的菜**；WebFlux 拿到说明书才开始下厨。

### 6.2 “自下而上传播”指什么

Reactor 里同时有**两个方向相反**的信号，容易混：

| 信号 | 方向 | 作用 |
|---|---|---|
| **订阅信号**（subscribe / request） | **自下而上**：链尾 → 链头 | “开始干活”的通知 |
| **数据信号**（onNext / onComplete / onError） | **自上而下**：链头 → 链尾 | 送结果、送错误 |

先把方位定死：**上游 = 代码里写在上面的部分（数据源头），下游 = 写在下面的部分（最终消费者）**。

```text
   代码上方 = 上游（source）
   ┌──────────────────────────────────────────────────────────┐
   │  context()            ← 最后被订阅，也是数据源头         │
   │      .flatMap(...)    ←                              ▲   │
   │      .map(...)        ←                              │   │ 数据
   │  ────────────────────────────────────────────────    │   │ 向下
   │  WebFlux subscribe()  ← 最先被订阅                      │   │
   └──────────────────────────────────────────────────────────┘
   代码下方 = 下游（subscriber）     订阅信号向上 ────────────┘
```

执行顺序：

```text
① WebFlux 对最外层（map 那个 Mono）调用 subscribe()
② map 内部去 subscribe 它的上游（flatMap 那个 Mono）
③ flatMap 内部去 subscribe 它的上游（deferContextual 那个 Mono）
   ---- 订阅信号走到链头，开始往回送数据 ----
④ deferContextual 的回调被执行，读出 ctx，发出 onNext(ctx)
⑤ flatMap 收到 ctx，调用 tasks.submit(...)，拿到任务结果
⑥ map 收到结果，算出 ResponseEntity
⑦ WebFlux 收到 ResponseEntity，序列化成 JSON 写回客户端
```

“自下而上”说的是 ①②③ 这段：**谁在最外层（代码最下面），谁最先被订阅，然后它一层层往代码上面通知。**

原因是每个算子的 `subscribe()` 实现里，都会**先去订阅自己的上游**：

```java
class MapMono<T, R> implements Mono<R> {
    final Mono<T> upstream;              // 我上面那一层
    final Function<T, R> f;

    public void subscribe(Subscriber<? super R> s) {
        upstream.subscribe(new MapSubscriber(s, f));  // ← 关键：先订阅上游
    }
}
```

所以 `subscribe()` 是一串嵌套调用：外层进、里层出，一路走到源头才停。

这也解释了 filter 里的 `contextWrite` 为什么“写在末尾却能被读到”——它是整条链的最外层：

```java
return chain.filter(exchange)          // ← 里面是整个 Controller 链路
        .contextWrite(view -> view.put(RequestContextDTO.class, context));
```

订阅信号从它经过时，它给下一个订阅者套上一层带 Context 的包装，再让包装后的订阅者去订阅上游。于是上游的 `deferContextual` 看到的，就是已经装好小票的那份 Context。

这不是“执行顺序反了”，而是**订阅信号本来就由链尾发起、向链头传递**。

## 七、为什么这里必须是响应式写法

1. **身份只能从 context 读**，而 context 只在订阅期可见 → 必须返回 `Mono`，用 `deferContextual` 登记回调；
2. **同步模式要等待任务**，等待不能占着线程 → 用 `Mono.delay` 做定时轮询，而不是 `Thread.sleep`；
3. **同步与异步共用同一条链**，只是末尾分叉 → 两种模式对客户端是两种语义，对服务端是同一条代码路径。

> 常见误解：“返回 `Mono` 就是把 `Mono` 发给前端”。不会——网络上只有 HTTP 状态码和 JSON，`Mono` 只存在于服务端内存里，详见 `如何理解Mono.md` 第三节。

## 八、状态码总表

| 场景 | 状态码 | 响应体 | 生成位置 |
|---|---|---|---|
| 异步提交成功 | 202 | `RunRes`（`state=RUNNING` 快照） | `chat()` |
| 同步完成（`DONE` / `NEEDS_INPUT` / `CANCELLED` / `REPLACED` 等） | 200 | `RunRes` | `chat()` |
| 同步任务失败（`FAILED`） | 502 | `RunRes` | `chat()` |
| 同步任务超时（`TIMEOUT`） | 504 | `RunRes` | `chat()` |
| 未认证 | 401 | `ErrorRes` | filter |
| 请求体不合法 | 400 | `ErrorRes` | `SupervisorErrorHandler` |
| 无权限查看证据 / 任务不属于当前身份 | 403 / 404 | `ErrorRes` | `SupervisorErrorHandler` |
| `requestId` 内容不同，或会话已有 `RUNNING` 任务 | 409 | `ErrorRes`（reason 透传） | `SupervisorErrorHandler` |
| 并发已满、每分钟任务数超限、任务存量达上限 | 429 | `ErrorRes` | `SupervisorErrorHandler` |
| `Content-Type` 不是 JSON | 415 | `ErrorRes` | `SupervisorErrorHandler` |
| 请求体过大 | 413 | `ErrorRes` | `SupervisorErrorHandler` |
| 存储不可用 | 503 | `ErrorRes` | `SupervisorErrorHandler` |
| 其他未预期异常 | 500 | `ErrorRes` | `SupervisorErrorHandler` |

> ⚠️ 注意最后两列的差别：**502 / 504 是 `chat()` 自己返回的正常 `RunRes`**（`state` 为 `FAILED` 或 `TIMEOUT`），不是 `ErrorRes`。客户端解析错误时不能一律按 `{code, message}` 处理，要先看状态码。

默认并发与配额（`config/SupervisorSettings.java`，均可用配置覆盖）：

| 配置 | 默认值 | 含义 |
|---|---|---|
| `supervisor.timeout` | `60s` | 任务绝对截止时间，超时后状态变 `TIMEOUT` |
| `supervisor.max-model-calls` | `16` | 单任务模型调用上限 |
| `supervisor.max-concurrent-requests` | `8` | 全局同时执行的任务数 |
| `supervisor.max-concurrent-per-user` | `2` | 单用户同时执行的任务数 |
| `supervisor.tasks-per-user-minute` | `10` | 单用户每分钟可新建任务数 |
| `supervisor.max-stored-tasks` | `10000` | 任务表存量上限 |

## 九、两种模式的时间线

假设任务实际耗时 5 秒。

**同步模式**

```text
t=0        请求到达；filter 解析身份；chat() 组装 Mono 并返回
t=0        订阅开始，读出 context，create() 建任务，start() 起独立订阅跑任务
t=0~5000   HTTP 连接保持；每 25ms 查一次状态（不占线程，也不写响应）
t=5000     任务 DONE → await 返回 → map 组装 ResponseEntity → 写 200 + 结果
```

前端体验：一次请求、一次响应，等待期间页面只能转圈。任务接近或超过 60 秒时，网关或浏览器可能先超时——这是本项目提供异步模式的实际原因。

**异步模式**

```text
t=0        请求到达；create() 建任务；start() 起独立订阅
t≈1ms     Mono 兑现 → 写 202 + 快照 → HTTP 请求结束
t=1~5000  任务继续在后台跑（独立订阅，与这个 HTTP 请求无关）
t=5000    任务 DONE，结果落库
之后       前端自行 GET /api/supervisor/runs/{runId} 取结果
```

前端体验：立刻拿到 `runId`，自行轮询。轮询间隔建议 500ms~1s，并在 `state` 不再是 `RUNNING` 时停止。

## 十、幂等与并发

| 情况 | 行为 |
|---|---|
| 相同 `requestId` + 相同内容（`sessionId` + `question` + `history`） | 直接返回原任务，**不会新建**；同步模式下会继续等原任务结束 |
| 相同 `requestId` + 不同内容 | 409，`message` 为“请求编号的内容不同” |
| 同一 `sessionId` 已有 `RUNNING` 任务 | 409，`message` 里带原 `runId` |
| 并发数 / 每分钟任务数 / 存量超限 | 429 `BUSY` |

内容哈希只覆盖 `sessionId`、`question`、`history`（`TaskStoreService.java:441`），不含 `requestId` 本身。

## 十一、常见误区

| 误区 | 正确理解 |
|---|---|
| WebFlux 会隔一段时间重复执行 `chat()` | 不会。一次 HTTP 请求只调用一次、只订阅一次、只读一次 context |
| 同步模式下线程被“卡住” | 没有。等待由 `Mono.delay` 定时器驱动，请求线程早已归还；被占住的是 HTTP 连接 |
| 异步模式下任务随请求结束而终止 | 不会。任务跑在 `start()` 的独立订阅上 |
| `202` 表示任务已完成 | 只表示“已接收”。任务可能还在 `RUNNING`，要查 `/runs/{runId}` |
| `502` / `504` 的响应体是 `ErrorRes` | 是 `RunRes`，`state` 分别为 `FAILED` / `TIMEOUT` |
| 传了 `history` 就会拼进服务端上下文 | 调用方历史只作“待核实资料”，服务端以持久化历史为准 |
| 同一个 `requestId` 重试会重复跑任务 | 不会，内容相同则返回原任务 |

## 十二、相关代码位置

| 位置 | 作用 |
|---|---|
| `filter/SupervisorRequestFilter.java:39` | 鉴权、解析 `Prefer`、`contextWrite` 写入上下文 |
| `controller/SupervisorController.java:63` | `chat()`：组装链路、映射状态码 |
| `controller/SupervisorController.java:200` | `context()`：`deferContextual` 读取身份，缺失时发 401 |
| `controller/SupervisorErrorHandler.java` | 把异常统一包装成 `ErrorRes` |
| `service/TaskService.java:91` | `submit()`：同步/异步分叉 |
| `service/TaskService.java:112` | `await()`：同步模式的 25ms 轮询 |
| `service/TaskService.java:151` | `start()`：任务的独立订阅 |
| `service/TaskStoreService.java:52` | `create()`：幂等、互斥、容量检查 |
| `service/TaskStoreService.java:451` | `validateRequest()`：入参校验规则 |
| `config/SupervisorSettings.java` | 超时、并发、配额等默认配置 |

## 十三、相关文档

- `docs/Supervisor使用说明.md`：接口清单与运行方式
- `docs/技术方案.md`：各接口的处理流程设计
- `docs/如何理解Mono.md`：Mono 的组装/订阅/兑现模型
- `docs/Reactor上下文说明.md`：Reactor context 与 `deferContextual`
