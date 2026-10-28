# 如何理解 Mono

> 本文回答四个最容易混淆的问题：
> Mono 到底是什么、它会不会发给前端、"返回 Mono"是不是就是异步、非阻塞又和它们什么关系。
>
> 配套阅读：`docs/Reactor上下文说明.md`（讲 context 如何在链路里传递请求信息）。

## 一、Mono 是什么

`reactor.core.publisher.Mono<T>` 是 Reactor 提供的一个类型，含义非常朴素：

> **一个"未来最多会有一个 `T` 类型结果"的容器，而且它可能以失败告终。**

它只会发出三类信号中的一种：

| 信号 | 含义 | 例子 |
|---|---|---|
| `onNext(T)` | 有值了（最多一次） | 任务跑完，拿到 `RunRes` |
| `onComplete()` | 结束了，但没有值 | `Mono<Void>`：删除记忆成功 |
| `onError(Throwable)` | 出错了，不会有值了 | 未认证 → `ResponseStatusException` |

对比 `Flux<T>`：`Mono` 是 0 或 1 个值，`Flux` 是 0 到 N 个值（本项目接口里基本只用 Mono）。

### 它不是"数据"，是"描述"

`Mono` 对象本身只描述"将来要做的事"，**不装数据、不占线程、也不执行任何业务代码**。真正干活要等到有人订阅它。

## 二、三个动作：组装、订阅、兑现

这是理解 Mono 的核心。以 `SupervisorController#chat` 为例：

```java
public Mono<ResponseEntity<RunRes>> chat(@RequestBody ChatParam param) {
    return context()                 // ① 组装：只是拼出一条流水线
            .flatMap(ctx -> tasks.submit(...)
                    .map(result -> ResponseEntity.status(code).body(result)));
}                                    // 方法返回时，什么都没执行
```

| 阶段 | 谁来做 | 发生了什么 |
|---|---|---|
| **① 组装** | 你的代码 | 把 `flatMap`、`map` 串起来，得到一张"说明书"。**不执行任何业务逻辑** |
| **② 订阅** | Spring WebFlux | 响应式框架对返回的 Mono 调用 `subscribe()`，流水线开始流动 |
| **③ 兑现** | 各个算子 | 到了 `map` 就转换、到了 `flatMap` 就去调 service……最终发出值或被框架写回 HTTP |

> 类比：**Mono 是菜谱，订阅是开火，兑现是上菜。**
> 写菜谱的时候厨房什么都没发生；只有开火（订阅）之后才按菜谱一步步做。

### 冷流：不订阅就不执行

Reactor 的 `Mono` 默认是**冷流**：每订阅一次，就从头执行一次。这也解释了为什么本项目 `context()` 能写成静态方法反复返回 Mono——每次订阅读的都是当前这次订阅自己的 context。

## 三、Mono 不会发给前端

这点必须讲清楚，因为 `Mono<ResponseEntity<RunRes>>` 这个签名很容易让人以为"返回了个 Mono 给浏览器"。

**网络上只传输普通 HTTP 响应**：

```
HTTP/1.1 200
Content-Type: application/json

{"runId":"r-123","state":"DONE","answer":"……","modelCalls":5,...}
```

`Mono` 只存在于服务端内存里，是写给 **Spring WebFlux** 看的：

1. 框架订阅这个 Mono；
2. 等它**发出一个值**（这里值是 `ResponseEntity`，包含状态码 + 响应体对象）；
3. 把响应体序列化成 JSON，写进 HTTP 响应，结束请求。

**只有第 3 步的数据会过网络。** 状态码也是从 `ResponseEntity` 里取的，不是从 Mono 里取的。

## 四、"返回 Mono" ≠ "异步返回给前端"

这是最常见的误解。关键在于：**Mono 兑现的时机，是你自己决定的。**

`TaskService.java:92-100`：

```java
return Mono.fromCallable(() -> create(identity, request))   // 建任务
        .subscribeOn(scheduler)
        .flatMap(task -> async
                ? Mono.just(store.view(identity, task))     // 异步：任务创建完就兑现
                : await(identity, task.runId));             // 同步：一直等到任务结束
```

同一个 `Mono<RunRes>` 类型，两条完全不同的时间线（假设任务跑 5 秒）：

**同步模式**

```
t=0      请求到达，Mono 开始等待
t=0~5000 HTTP 连接挂着，一个字节都没写；Mono 每 25ms 查一次状态
t=5000   任务 DONE，Mono 兑现 → 框架写响应 → 前端收到 200 + 结果
```

**异步模式**

```
t=0      请求到达，任务创建
t=~1ms   Mono 兑现 → 框架写响应 → 前端收到 202 + runId（请求到此结束）
t=1~5000 任务在后台线程池继续跑，与这个 HTTP 请求无关
t=5000   前端自己再发 GET /runs/{runId} 取结果
```

所以：

- **同步 vs 异步，不是"返不返回 Mono"的区别，而是"这个 Mono 等多久才兑现"的区别。**
- 异步模式下，任务能活过请求，是因为任务执行是**另一条独立订阅**（`TaskService.java:151` 的 `start()`），不在 HTTP 的 Mono 链里。

## 五、非阻塞又是另一回事

三个概念经常被揉在一起，这里分开：

| 概念 | 说的是什么 | 对前端可见吗 |
|---|---|---|
| **非阻塞** | 服务端等待 I/O 时**不占着线程**，把线程还给线程池 | 不可见。前端看到的一次请求一次响应没变 |
| **异步响应** | 先回 202 + `runId`，结果稍后另取 | 可见。前端要自己轮询 |
| **服务端推送** | SSE / WebSocket 主动推结果 | 可见。但不是普通请求-响应 |

**非阻塞优化的是并发吞吐，不是单个请求的速度。** 同步模式下等待期间的线程是空闲的（`Mono.delay` 是定时器回调，不是 `Thread.sleep`），但 HTTP 连接、中间网关、浏览器都得陪着等，所以长任务仍会撞超时——这才是本项目提供 `Prefer: respond-async` 的实际原因。

而且回到 HTTP 语义：**响应一旦结束，服务端不能凭空再往里追加数据**。"完成后推给前端"只能靠前端再来取（轮询），或另开通道（SSE / WebSocket）。

## 六、本项目里出现过的 Mono 用法

| 写法 | 含义 | 位置 |
|---|---|---|
| `Mono.just(x)` | 立刻发出一个现成值 | `TaskService.java:97`、`SupervisorController.java:188` |
| `Mono.error(e)` | 发出错误信号（不是 `throw`） | `SupervisorController.java:191` |
| `Mono.fromCallable(() -> ...)` | 把一段同步代码包装成 Mono，订阅时才执行 | `TaskService.java:92`、`SupervisorController.java:131` |
| `Mono.fromRunnable(() -> ...)` | 包装一段无返回值的同步代码 | `SupervisorController.java:156` |
| `Mono.defer(() -> ...)` | 延迟到订阅时才创建内部 Mono | `TaskService.java:170` |
| `Mono.deferContextual(...)` | 延迟到订阅时读取 Reactor context | `SupervisorController.java:181` |
| `.subscribeOn(scheduler)` | 指定上面那段同步代码在哪个线程池跑 | `TaskService.java:93` |
| `.map(x -> y)` | 纯转换：值 → 新值，不产生新的异步动作 | `SupervisorController.java:76` |
| `.flatMap(x -> Mono)` | 转换并**摊平**：内部返回的 Mono 并入同一条流 | `SupervisorController.java:69` |
| `.then(Mono)` / `.thenReturn(x)` | 前一步完成后接着执行，丢弃前一步的值 | `TaskService.java:118`、`SupervisorController.java:163` |
| `.switchIfEmpty(Mono.error(...))` | 上游没发出值时改走错误分支 | `TaskService.java:175` |
| `.doFinally(...)` | 无论成功/失败/取消，最后都执行一次（常用于清理） | `TaskService.java:159` |
| `.subscribe(...)` | 真正触发执行（**只应出现在框架边界或内部任务启动处**） | `TaskService.java:165` |

### `map` 与 `flatMap` 的选择

- 转换后的东西还是普通对象 → `map`
- 转换后的东西是另一个 `Mono`，需要"打开再并进当前流" → `flatMap`

用 `map` 套 `Mono` 会得到 `Mono<Mono<T>>`，这就是常说的"套娃"。

## 七、和 Reactor context 的关系

`Mono` 是"结果容器"，`Context` 是"跟着订阅走的小票"。两者配合才能做到：

```java
return context()                 // 订阅时才知道"你是谁"
        .flatMap(ctx -> tasks.submit(ctx.identity(), ...));
```

详细机制见 `docs/Reactor上下文说明.md`。

## 八、常见误区

| 误区 | 正确理解 |
|---|---|
| 返回 Mono 就是把 Mono 发给前端 | Mono 不过网络，前端只收到 HTTP 状态码 + JSON |
| 返回 Mono 就等于异步响应 | Mono 只是"稍后才有值"的容器，等多久由链路决定 |
| 写完 Mono 链就执行了 | 冷流，不订阅不执行；方法返回时往往什么都没干 |
| 非阻塞 = 立即返回 | 非阻塞只是等待时不占线程，响应仍在结果就绪时发出 |
| 非阻塞 = 更快 | 单请求延迟不变，提升的是并发吞吐 |
| 可以在普通方法体里读 context | 读不到，必须在订阅期算子（`deferContextual` 等）里读 |
| `Mono` 里多订阅几次会共享结果 | 冷流每次订阅都重新执行；需要共享要用 `cache()` 等算子 |

## 九、参考资料

官方只有英文文档，中文均为社区翻译，版本可能滞后，冲突时以官方为准。

- 官方（英文）
  - [Reactor Core Reference Guide](https://projectreactor.io/docs/core/release/reference/)
  - [Mono Javadoc](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Mono.html)
  - [Adding a Context to a Reactive Sequence](https://projectreactor.io/docs/core/release/reference/advancedFeatures/context.html)
- 社区中文
  - [springdoc.tech 中英对照（Reactor Core）](https://springdoc.tech/reactor-core/corefeatures/)
  - [Reactor3 中文文档（用户手册）](https://www.cnblogs.com/crazymakercircle/p/14292098.html)

## 十、相关代码位置

| 位置 | 作用 |
|---|---|
| `controller/SupervisorController.java#chat` | 组装 Mono 链、按同步/异步映射状态码 |
| `controller/SupervisorController.java#context` | `deferContextual` 读取请求上下文，缺失时发 401 |
| `service/TaskService.java#submit` | 同步/异步两条分支的分叉点 |
| `service/TaskService.java#await` | 同步模式的轮询等待（`Mono.delay` + 递归 `defer`） |
| `service/TaskService.java#start` | 任务的独立订阅，使其生命周期与 HTTP 请求解耦 |
