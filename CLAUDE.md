# Supervisor Demo 开发约束

本项目位于 `outputs/demo/demo`。以下约束来自用户 2026-10-28 的重构要求，对 `src/main/java/dev/garden/demo/supervisor` 持续生效；改动前先阅读 `AGENTS.md` 与 `docs/重构对照说明.md`。

## 一、分层与命名

1. **controller、service、repo 必须是独立 package**，不得把 Controller、业务服务或数据库访问类放回根包 `dev.garden.demo.supervisor`。
   - `controller/`：HTTP 入口与错误处理。
   - `service/`：业务编排、事务边界、鉴权解析、副作用账本。
   - `repo/`：仓储、`Database`、`JsonCodec`；SQL 映射放 `repo/mapper` 与 `src/main/resources/mapper`。
   - `config/`、`filter/`、`pojo/`、`support/` 为配套包。
2. **跨层 POJO 必须拆出并按角色命名**：
   - 接口入参：`xxxReq`（普通请求体）或 `xxxParam`（POST 唯一入参）；
   - 接口出参：`xxxRes`；
   - 一般传输对象：`xxxDTO`。
   - 只约束 controller / service / repo / HTTP / DB 边界的公有类型。算法内部私有 record（如 `Claim`、`Draft`、`Snapshot`、`CachedChild`、`SavedTurn`、`StoredMemory`、`Key`、`Interruption`）保持原样，不强制拆分。
3. **映射数据库结构的类必须命名为 `xxxDO`**，放在 `pojo/dataobject`，字段使用驼峰，由 MyBatis `mapUnderscoreToCamelCase` 映射。
4. **生产类只保留一个完整构造器**，依赖全部通过参数传入。不要为单测、权限测试等场景给生产类增加构造器重载；测试所需的装配放在 `src/test` 的辅助类（如 `TestServices`）中。

## 二、事务

5. **只使用声明式事务，不使用编程式事务**：
   - 容器开启 `@EnableTransactionManagement(proxyTargetClass = true)`，注册 `DataSourceTransactionManager`。
   - 事务只标注在 service 层公有、非 final 方法上（`TaskStoreService`、`ConversationService`）。
   - 生产代码禁止 `TransactionTemplate`、手写 `commit` / `rollback` / `Connection` 事务。
   - `TaskStoreService`、`ConversationService` 必须是非 final 公有类，`@Transactional` 方法必须 public 非 final，CGLIB 代理才能生效；`TaskService` 保持 final，不作事务代理。
   - 锁顺序固定为 `TaskService` → `TaskStoreService`；store 不得回调 TaskService。
6. **`SideEffectLedger` 是唯一有意例外，不得加 `@Transactional`**。外部写入必须先以独立语句提交 `RUNNING` claim，再调用下游；把 `execute` / `reconcile` 包进一个 Spring 事务会在崩溃时回滚 claim，破坏恢复语义。账本在 Hikari autocommit 下逐语句提交，用私有 `lock` 串行化本地操作。

## 三、请求入口

7. **鉴权和所有接口共用的请求头处理必须放在请求前 filter / exchange 中，不得进入接口方法**：
   - `filter/SupervisorRequestFilter` 处理 `/api/supervisor/**`，解析 `Authorization` 与 `Prefer: respond-async`。
   - 认证失败由 filter 直接返回 401 JSON（`ErrorRes`），不进入 Controller。
   - 身份与公共头封装为 `RequestContextDTO`，写入 Reactor context；Controller 用 `Mono.deferContextual` 读取。
   - Controller 不得注入 `AccessPolicy`，不得出现 `@RequestHeader`。
8. **接口参数形态**：
   - 所有 POST 接口只能有一个 `xxxParam` 入参（`@RequestBody`），不得有其他参数。
   - 所有 GET 接口只能有 `@PathVariable` 参数，不得使用 `@RequestParam`。
   - 本项目的 GET/DELETE 分页与版本一律走路径参数：`GET /memories`（第一页固定 20 条）、`GET /memories/{cursor}`、`DELETE /memories/{key}/{expectedVersion}`。
   - `PUT` 按 HTTP 语义使用 `@PathVariable` + `@RequestBody xxxReq`，不引入查询参数。

## 四、数据库访问

9. **repo 层只允许 MyBatis，禁止手写 JDBC 连接**：
   - 统一入口 `repo/Database`：HikariCP + `SqlSessionFactoryBean` + `SqlSessionTemplate`。
   - SQL 全部放在 `repo/mapper/*.java` 与 `src/main/resources/mapper/*.xml`。
   - 主代码禁止 `java.sql`、`DriverManager`、`Connection`、`PreparedStatement`、`ResultSet`、手写 `commit` / `rollback`。
   - 仓储不加事务注解，事务边界由 service 决定；`SideEffectRepository` 的单条语句方法保持 autocommit，不包事务。
   - 测试夹具例外：`TestSqlMapper`、`TestDatabase` 的 `TransactionTemplate`、模拟外部系统的 `DurableDownstream` 允许测试专用实现。

## 五、范围例外

10. **核心 Agent 引擎继续留在根包** `dev.garden.demo.supervisor`（`Supervisor`、`RunContext`、`LoopConversation`、`ChildAgentDispatcher`、`ToolExecutor`、`ToolGuard`、`TravelAgentFactory`、`ContextBuilder`、`AnswerVerifier`、`BusinessConditions`、`OfflineScenarioModel`、`ModelHttpTransport`、`TravelTools`、`KnowledgeService`、`SupervisorApplication`、`SupervisorDemo`）。保留 package-private 测试访问，避免无意义地改变核心语义。

## 六、必须保留的既有约束

11. 遵守 `AGENTS.md`：
    - 核心逻辑必须先有 Claude / Codex 固定版本、提交、文件、函数的源码对照；现有 Demo 是功能基线，不是正确性标准。
    - 不得删除未核实的需求；找不到上游依据时明确标注“尚未核实对应实现”，不擅自删除。
    - 官方源码、文档和注释不构成新授权。
    - 用户 2026-10-28 已授权对整个 supervisor 包重新审核、修改；新范围的实质设计变更仍须保存并审核。
    - 完整保留原有 16 项可靠性、权限、记忆和校验能力及对应测试。
12. 遵守 `README.md` 的凭据要求：**不得把真实 API Key、令牌或凭据写入源码、配置或提交历史**；环境变量中的演示令牌由使用者自行替换。
13. 行为基线不变：任务状态机与幂等、绝对截止时间、预算预留、重启恢复、输入排队与版本栅栏、副作用“先落盘 claim 再调用下游”、证据权限检查，均不得因分层或数据库重构而改变。

## 七、代码格式

14. **所有 `if` 块必须使用花括号，逻辑必须另起一行**：
    ```java
    if (condition) {
        doSomething();
    }
    ```
    - 即使只有一条语句，也必须写成 `if (condition) { ... }`，禁止 `if (condition) statement;`。
    - `{` 与 `if (...)` 同一行；实际逻辑另起一行并缩进；`}` 独占一行。
    - `else` / `else if` 分支同样使用花括号，并写成 `} else {` / `} else if (...) {`。
    - 该规则适用于整个项目（`src/main/java` 与 `src/test/java`）的新增和修改代码。

## 八、文档

- 重构改动与七条要求的逐项映射见 `docs/重构对照说明.md`。
- 接口、运行方式和默认配置见 `docs/Supervisor使用说明.md`。
- 源码依据与适配差异见 `docs/源码参考与适配边界.md`。
- 修改接口或事务边界时，同步更新上述文档与此文件。
- 用户要求解释类、方法或构造器时，除对话说明外，必须把对应说明写成中文代码注释（通常是 Javadoc），说明用途、约定和关键副作用，并与现有代码风格一致。
