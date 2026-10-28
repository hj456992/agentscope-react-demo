# Reactor context（上下文）入门说明

> 面向第一次接触 WebFlux / Reactor 的读者。读完本文应能回答三个问题：
> context 是什么、为什么必须用 `deferContextual` 读、本项目为什么要这样写。
>
> 本文只解释概念，不改变任何行为约束。接口与分层约定仍以 `CLAUDE.md` 与 `docs/Supervisor使用说明.md` 为准。

## 一、先看要解决什么问题

项目里每个接口都需要知道两件事：

- **你是谁**：请求者身份，用于鉴权、任务归属、记忆读写权限；
- **你要不要异步返回**：请求头 `Prefer: respond-async`。

最直接的办法是每个接口都声明请求头参数：

```java
// 反面示例：每个接口都要重复声明、重复鉴权
public Mono<...> chat(@RequestBody ChatParam p,
                      @RequestHeader("Authorization") String token,
                      @RequestHeader("Prefer") String prefer) { ... }
```

这样做的问题：

1. 接口方法签名被公共信息污染，加一个接口就要再写一遍；
2. 鉴权逻辑容易在各接口里各写一套，行为不一致；
3. 本项目 `CLAUDE.md` 第七条明确要求：鉴权与公共请求头在 filter 里统一处理，Controller 不得出现 `@RequestHeader`。

所以需要一个"存起来、各处可取"的地方。存到哪里，就是 context 要回答的问题。

## 二、两个"储物柜"：ThreadLocal 与 Context

### ThreadLocal：挂在“线程”上的储物柜

传统 Spring MVC 一个请求从进来到出去都在同一个线程上，因此可以把公共信息放进 `ThreadLocal`，链路任何位置都能取。

### 为什么 WebFlux 里不适用

WebFlux 是非阻塞的：一个请求在等待模型响应、等待数据库时，线程会被让出去处理别的请求，回来时可能已经换了一个线程。此时：

- 放到“线程的储物柜”里的东西，取的时候可能换了柜子；
- 更糟的是，这个线程可能正在服务另一个请求，取到的是别人的数据。

> 打个比方：ThreadLocal 是把备注写在**厨师的工牌**上——厨师换班，备注就丢了或者串到别人头上。
> Reactor context 是把备注写在**这张订单的小票**上——小票跟着订单走，谁来做菜都能看到。

### Context 是什么

Reactor 从 3.1 起提供的 `reactor.util.context.Context`：

- 一个**类似 Map 的键值仓库**，键和值都是 `Object`，因此可以直接拿 `Class` 对象当 key；
- **不可变**：`put` 返回新的 Context，不改原来那份；
- **不能存 null**；
- 绑定的不是线程，而是**这次订阅**（`Subscriber`），沿着订阅传播机制向上游可见。

在本项目中，`SupervisorRequestFilter` 写入的键就是 `RequestContextDTO.class`，值是 `{ identity, async }`。

## 三、先理解 Mono 与“订阅”

这是理解 context 的前提。

Controller 方法被调用时，**并没有真的开始干活**。它只是返回一个 `Mono`：

```
chat() 被调用  →  返回一个 Mono（相当于菜谱：等开火时要做这些事）
                        ↓
Spring（WebFlux）对这个 Mono 调用 subscribe()   ← 这才是“开火”
                        ↓
菜谱里的每一步这时才真正执行
```

由此得到两个结论：

1. **写方法体的那一刻**：还没开火，小票还没递过来，读不到 context；
2. **订阅的那一刻**：`Mono.deferContextual(...)` 里登记的回调才执行，这时才读得到。

这就是 `context()` 不能直接返回 `RequestContextDTO`、只能返回 `Mono<RequestContextDTO>` 的原因——它只是承诺“订阅时会去取”。

## 四、本项目里的完整流程

以 `POST /api/supervisor/chat` 为例。

### 第 1 步：请求到达，filter 先执行

`filter/SupervisorRequestFilter.java:53-55`：

```java
boolean async = "respond-async".equalsIgnoreCase(
        exchange.getRequest().getHeaders().getFirst("Prefer"));
RequestContextDTO context = new RequestContextDTO(identity, async);
return chain.filter(exchange)
        .contextWrite(view -> view.put(RequestContextDTO.class, context));
```

小票上此时写着：`{ 身份 = 用户A, 是否异步 = true }`。

### 第 2 步：Controller 的 `chat()` 被调用

`controller/SupervisorController.java:63`：

```java
return context()               // 只是“登记”：等订阅时去小票上读
        .flatMap(ctx -> ...);  // 读到了，再用 ctx 去提交任务
```

`chat()` 执行到这里就返回了，此时**还没有读到任何东西**。

### 第 3 步：Spring 订阅这个 Mono

- 订阅从链尾（`contextWrite` 所在层）开始，一层层向“源头”方向传播；
- 每经过一个 `contextWrite`，就往小票上加内容；
- 因此**越往上游走，小票越满**——这就是“写在链尾，上游才读得到”的原因；
- 走到 `deferContextual` 时，小票已装好身份与 async，`ctx` 即为 `{ 身份=用户A, async=true }`；
- `flatMap` 拿着 `ctx` 调 `tasks.submit(ctx.identity(), param.toRequest(), ctx.async())`，继续后续逻辑。

> 注意：`contextWrite` 通常写在链的最后一行，但它**最先执行**，因为订阅信号是自下而上传递的。

## 五、一个能跑的最小例子

```java
record Student(String name) {}

Mono<String> r = Mono.just("你好")
        .flatMap(s -> Mono.deferContextual(view ->
                Mono.just(s + "，" + view.get(Student.class).name())))  // 订阅时才读
        .contextWrite(view -> view.put(Student.class, new Student("小明"))); // 塞小票

// 订阅 → 输出 "你好，小明"
```

把 `.contextWrite` 删掉，运行时会报错说找不到 `Student`——因为没人往小票上写。

用 `StepVerifier`（`reactor-test`）还可以显式断言 context：

```java
StepVerifier.create(r)
        .expectAccessibleContext()
        .contains(Student.class)
        .then()
        .expectNext("你好，小明")
        .verifyComplete();
```

## 六、`context()` 三行代码的含义

`controller/SupervisorController.java:178-198`：

```java
Mono.deferContextual(view ->
        view.hasKey(RequestContextDTO.class)        // 小票上有没有这一项？
                ? Mono.just(view.get(RequestContextDTO.class))  // 有 → 把内容交出去
                : Mono.error(new ResponseStatusException(       // 没有 → 报“未认证”
                        HttpStatus.UNAUTHORIZED, "请提供有效身份令牌。")));
```

- `hasKey`：Reactor context 不允许存 null，所以 `hasKey` 为 `true` 时 `get` 一定非空；
- `Mono.just`：直接发射现成对象；
- `Mono.error`：发出的是**错误信号**，不是 `throw`，让响应式链路按出错分支继续传播；
- 401 最终由 `controller/SupervisorErrorHandler.java` 包装成 `{"code":"UNAUTHORIZED","message":"请提供有效身份令牌。"}`。

## 七、常见误区

| 误区 | 正确理解 |
|---|---|
| 在普通方法体里直接读 context | 读不到。必须放在 `deferContextual` / `transformDeferredContextual` 等订阅期算子中 |
| 认为 context 是全局变量 | 每个订阅各有一份，互不干扰 |
| 在 `contextWrite` 之后（下游）去读 | 读不到。写入只对**上游**可见，“先写后读”在代码里是反着写的 |
| 把 `ThreadLocal` 里的 MDC 直接搬过来 | 需要在 `doOnEach` 中把 context 值取出再写入 MDC，否则日志会串 |
| 往 context 里存 null | 不允许，会直接抛异常 |

## 八、有没有官方中文文档

**Project Reactor 官方没有发布中文版参考指南**，官方文档只有英文。中文资源均为社区翻译或第三方整理，版本可能滞后，遇到与官方不一致时以英文官方为准。

- 官方（英文）：
  - [Reactor Core Reference Guide — Adding a Context to a Reactive Sequence](https://projectreactor.io/docs/core/release/reference/advancedFeatures/context.html)
  - [Context API Javadoc](https://projectreactor.io/docs/core/release/api/reactor/util/context/Context.html)
  - [Context-Propagation Support（3.5+，用于与 Micrometer/日志框架打通）](https://projectreactor.io/docs/core/release/reference/advanced-contextPropagation.html)
- 社区中文：
  - [springdoc.tech 中英对照（Reactor Core）](https://springdoc.tech/reactor-core/corefeatures/)：可切换中英文，跟进较新版本；
  - [Reactor3 中文文档（用户手册）第 8.8 节 Context](https://www.cnblogs.com/crazymakercircle/p/14292098.html#9)：专门讲 Context 的一节；
  - [get-set/reactor-core master-zh](https://github.com/get-set/reactor-core/tree/master-zh/src/docs/asciidoc)：社区翻译源码，不定期跟进；
  - [knowledge-things/reactor-core](https://github.com/knowledge-things/reactor-core)：基于 3.2.0 的旧版翻译，仅作参考。

## 九、相关代码位置

| 位置 | 作用 |
|---|---|
| `filter/SupervisorRequestFilter.java` | 鉴权、解析 `Prefer`，用 `contextWrite` 写入 `RequestContextDTO` |
| `controller/SupervisorController.java#context` | 用 `deferContextual` 读取，缺失时发 401 |
| `controller/SupervisorErrorHandler.java` | 把 `ResponseStatusException` 统一包装成 `ErrorRes` |
| `pojo/dto/RequestContextDTO.java` | 上下文内容：`{ identity, async }` |
