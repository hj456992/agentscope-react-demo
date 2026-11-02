# Supervisor 全包实现说明

v3.5，2026-10-29。核心采用固定 Codex 提交的 MultiAgent V2 与本地模型摘要路径；主子会话共用显式模型—工具循环。模型最终文本原样返回，取消固定改写/意图阶段、固定澄清模板和必经第二个答案模型。v3.5 在保持上述行为基线的同时补齐七项运行策略：业务语义校验、确定性事实校验、纠错上限、多 Agent 量化门槛、统一失败分类、压缩保真与话题切分。2026-11-01 追加确定性的 `plan_agents` 多 Agent 计划校验和轻量语义循环检测，两者各有独立开关，默认关闭。源码行号与尚未对齐项见[源码参考与适配边界](源码参考与适配边界.md)，执行证据见[实施记录](实施记录.md)。

保留用户已审阅的 controller/service/repo/pojo 分层、MyBatis、声明式事务、请求filter和HTTP接口；对应要求见[重构对照说明](重构对照说明.md)。

## 整体执行

```mermaid
flowchart TD
    U[用户原话] --> A[认证与持久任务受理]
    A --> H[恢复真实消息 输入队列与邮箱]
    H --> C{模型输入超过窗口}
    C -->|是| K[无工具摘要请求 成功后替换历史]
    C -->|否| M[模型选择下一步]
    K --> M
    M -->|工具请求| V{工具名 参数与权限校验}
    V -->|不通过| E[返回结构化工具错误]
    V -->|通过| L{同工具同参数重复}
    L -->|提醒| E
    L -->|终止| X[按 NEEDS_INPUT 或 FAILED 收尾]
    L -->|通过或豁免| S{语义目标高度相似}
    S -->|提醒| E
    S -->|要求确认| X
    S -->|通过或未开启| P{是否调用 plan_agents}
    P -->|是| PL[校验计划 依赖 权限 批次与成本 保存 plan:v1:current]
    PL --> R
    P -->|否| G{spawn_agent 或 followup_task}
    G -->|是| CG{调用图回边或协作目标相似}
    CG -->|回边成环或语义提醒| E
    CG -->|语义要求确认| X
    CG -->|通过| Q[执行查询工具或 V2 协作]
    G -->|普通工具| Q
    E --> R[按调用编号保存结果]
    Q --> R
    R --> H
    M -->|询问| W[保存原提问 等待真实答案]
    W -->|答案到达| R
    M -->|最终文本| F[再查输入与邮箱 原样交付]
    A <--> DB[(PostgreSQL 检查点 预算 会话与邮箱)]
```

图中三道循环检查按顺序执行：`loop:v1:tool-calls` 拦截同工具同参数重复，`loop:v1:semantic-goals` 用字符双元组相似度识别换了措辞的同类目标，`loop:v1:agent-edges` 在 `spawn_agent` 和 `followup_task` 前检查调用图回边。前两道默认开启精确检测、语义检测默认关闭；命中后先返回 `LOOP_DETECTED` 工具错误，继续重复才转 `NEEDS_INPUT` 或 `FAILED`。`plan_agents` 只在 `multi-agent-planner-enabled=true` 时出现在工具表中，输出经校验的计划和批次，但不创建子会话，模型仍需按批次调用 `spawn_agent`。

天气、车票、行程是角色提示词和工具集合。主模型可以直接使用可见查询工具，也可以创建独立子会话；没有“必须先天气再车票再校验”的固定链。子会话使用相同循环，自己决定查询、继续协作或结束。离线脚本只模拟示例场景，不能证明真实模型的理解或规划效果。

`rewrittenQuery` 保留旧接口字段，表示当前输入而非隐藏改写；`intent` 是兼容展示字段，不驱动前置分类。最终`answer`来自实际模型文本；`evidence`与`agents`是附件，不把模型答复改成模板。

## 独立 Agent 与 V2 协作

| 模型工具 | 参数与行为 |
|---|---|
| `spawn_agent` | `task_name,message`，可选`agent_type,fork_turns`；角色为weather_agent/train_agent/itinerary_agent，fork为all或none；返回已受理子身份，不等待最终结果 |
| `send_message` | `target,message`；只入收件人邮箱，不启动闲置会话 |
| `followup_task` | `target,message`；闲置子会话开始新回合，运行中的会话排队；不把普通消息与新任务混为一类 |
| `wait_agent` | 可选`timeout_ms`；等待调用者自己的消息或新输入；不消费消息、不取消子会话，不接受legacy ids参数 |
| `list_agents` | 可选`path_prefix`；查看树内会话及状态 |
| `interrupt_agent` | `target`；返回中断前状态，只停止目标当前回合，不删除session，不级联取消后代 |
| `request_user_input` | `questions`包含问题编号、标题、正文与可选选项；保留提问原文和call ID，收到实际回答才生成工具结果 |
| `plan_agents` | `goal,tasks`；每项任务含`task_name,agent_type,objective,depends_on`；仅`multi-agent-planner-enabled=true`时注册，返回计划与批次但不创建子会话 |

接受前失败/取消清理临时子记录；接受后子会话具有独立订阅、取消代数和检查点。父正常完成或CANCEL不撤回已接受子会话。子回合完成/失败通知进入直接父邮箱；普通中断不是最终完成，之后可followup。同树共享调用额度和绝对期限，父子独立取消不会获得新额度。


协作操作还保存单独回执：稳定回合号 + 工具调用号对应一次 spawn/send/followup/interrupt。操作已受理但工具消息尚未保存时重启，恢复原回执，不重复操作。普通读缓存、协作回执和外部写入账本分别处理不同防重边界。

fork默认保留父消息作为历史，添加继承身份说明；历史工具调用不会在子会话再次执行，父审批和父执行状态不直接成为子权限。`fork_turns=none`从新历史开始。当前模型工厂不提供上游model/reasoning_effort覆盖参数，差异不能用“角色相同”掩盖。

### 多 Agent 计划与派发

```mermaid
flowchart TD
    A[模型提出目标与子任务] --> B{plan_agents 已开启}
    B -->|否| H[直接选择 spawn_agent 或单 Agent 回答]
    B -->|是| C[校验任务名 类型 objective 与依赖]
    C -->|不合法| R[返回 REJECTED 及固定原因]
    C -->|合法| D[按拓扑依赖生成批次]
    D --> E{权限 深度 子会话数与量化门槛}
    E -->|不通过| S[返回 SINGLE_AGENT 及原因]
    E -->|通过| F[保存 plan:v1:current 并返回 MULTI_AGENT]
    F --> G[模型按批次调用 spawn_agent]
    G --> L{调用图回边或协作目标相似}
    L -->|命中| X[返回 LOOP_DETECTED 或暂停本轮]
    L -->|通过| N[创建或触发子会话]
```

计划器只做四件事：校验结构，按 `depends_on` 拆批，复用 `MultiAgentGate` 估算 token 与时延，把计划写入检查点。计划结果不自动派发；是否创建子会话仍由模型在后续步骤决定。

## 输入、等待与恢复

用户输入以原顺序逐条入队，在模型安全边界加入会话；不预先猜用户意图，也不把多个输入拼成一条。收到父子消息时附发送者、种类和正文，代理消息不能被当作真实用户授权。队列投递与循环检查点配合确认，避免drain后崩溃丢输入。

询问等待时任务为NEEDS_INPUT，原工具请求保持未回答；通过指令接口提交答案后，按原call ID恢复。单题普通文本原样回传；多题可提交`{"answers":{"问题编号":{"answers":["回答"]}}}`。保存提问后不能用“等待用户补充”伪造工具成功结果。

```mermaid
flowchart TD
    I[指令号 任务号 动作和正文] --> S{动作是停止}
    S -->|是| X[立即取消 不校验版本]
    S -->|否| V{身份 版本 状态有效}
    V -->|否| E[返回拒绝 不修改旧任务]
    V -->|是| D{动作}
    D -->|省略或消息| Q[持久输入批次]
    Q --> B[恢复等待答案或下次模型边界消费]
    B --> C[保存消息检查点 确认投递]
    C --> F{完成前还有输入或消息}
    F -->|有| B
    F -->|无| O[按消费版本提交]
    D -->|切换| N[检查容量 创建新根任务]
```

HTTP断开只停止等待；后台任务继续。追加输入不重置默认期限或模型次数。任务、子身份、邮箱、工具消息和压缩替换历史写入PostgreSQL；重启沿用用量与期限，旧代执行不能提交新代结果。共享库只解决多实例读取同一份事实，不能代替分布式执行租约。相同会话取消后再次提交，会继承已完成工具历史，为未完成调用补中断结果，再追加真实新输入。当前 HTTP 仍沿用新请求建立新根任务的约定，旧树已受理的子会话继续归原根管理。

## 上下文与终答

ContextBuilder只重建可信提示、日期和历史信任说明。旧问答以真实user/assistant消息初始化一次，不再塞进SYSTEM的“用户条件清单”。窗口过长时由`compact`角色生成交接摘要，压缩请求不提供业务工具；只有完整摘要成功后才替换正式历史。内部超窗从临时历史头部移除，并同步处理工具调用/结果；失败、取消和无效摘要保留旧历史。

当前用户问题优先保留全文，只按最小摘要预留计算预算；旧条件和最近工具配对按完整摘要预留，放不下就放弃；摘要最后放，只有完整摘要确实放不下时才截断并标记 `summaryTruncated`。旧摘要、代理消息和内部通知不作为新用户原文保留。替换成功写“摘要忠实度”事件，低于阈值且开关开启时写告警；`SummaryFaithfulness` 只做确定性 token 级评测，不证明语义忠实。替换历史、摘要及代数写在同一检查点，协议1旧记录可迁移，恢复不重新注入已压缩request.history。

主模型的终答不自动经过AnswerVerifier，也不会被能力说明/澄清模板覆盖。KnowledgeService仍能提供文档号、版本、位置和有效期；AnswerVerifier仍是可独立复用组件，但当前不是核心强制校验链。不能因为保留了这两个类，就宣称自由文本答复、引用忠实性或全部自然语言条件已自动验证。

## 16 项能力与边界

| # | 能力 | 当前处理与限制 |
|---|---|---|
| 1 | 子Agent失败 | 独立状态与完成/失败通知；父模型决定修正，不强制重跑整树 |
| 2 | 执行中新输入 | 有序队列、持久投递确认、完成版本栅栏 |
| 3 | 时间与次数 | 共享根预算、绝对期限、每次真实请求预留；恢复不归零 |
| 4 | 工具幂等 | 读缓存与写账本分开；工具调用号不能替代业务操作号 |
| 5 | 多Agent理由 | `MultiAgentGate` 量化并行度、token、时延和深度；默认 `ADVISORY` 只记录决策，`ENFORCE` 执行硬上限；`plan_agents` 提供默认关闭的确定性计划校验，不自动派发 |
| 6 | 传递与去重 | 带来源邮箱、call ID配对、按回合去重通知；fork不复制父执行权 |
| 7 | 多轮与长期记忆 | 会话历史和显式确认偏好分别保存；`TopicSegmenter` 只改变历史装配范围，不删除旧会话 |
| 8 | 召回正确但回答错 | `AnswerFactChecker` 确定性比对数值和日期；冲突或高风险无证据数值降级 `PARTIAL`；语义校验默认关闭 |
| 9 | 上下文过长 | 模型摘要、当前问题优先、完整摘要预留、边界截断标记、token 级忠实度告警、失败保留旧历史 |
| 10 | 单工具失败 | 错误回模型，已成功证据不被其他失败覆盖 |
| 11 | 重试分类 | `FailureClassifier` 统一分类；429 限流可重试、配额耗尽不重试；识别 `Retry-After` |
| 12 | 外部副作用 | 先落RUNNING claim，再调用下游；未知结果查状态，不直接重复写 |
| 13 | 参数格式错 | Schema 与工具字段校验；连续两次同工具 schema 错误后在本运行禁用，错误配对回模型 |
| 14 | 参数含义错 | `BusinessRules` 在数据源前校验过去日期、超出未来窗口和同城路线，返回 `BUSINESS_RULE_VIOLATION`；规则可由 `supervisor.policy.business-rules` 声明，按工具版本远程下发未实现 |
| 15 | 不存在工具 | 运行期工具快照拒绝并回模型；第二次要求澄清，第三次进入 `FAILED`，不暴露内部工具清单 |
| 16 | 无权限工具 | 认证、角色工具可见性、运行时与历史/缓存/证据回放重新检查 |

SideEffectLedger在调用外部系统前独立提交claim，故有意不加`@Transactional`；未知结果可在任务取消后继续核对。下游没有稳定幂等和查询能力时不承诺恰好一次。默认天气/车次是只读演示数据，写入恢复通过专用测试适配器验证。

## 如何运行

环境：Java 17、Maven。默认真实模型，需要环境凭据；天气、车票仍为本地演示数据。

```bash
mvn -q -DskipTests package
# 先在环境中配置 DEEPSEEK_API_KEY，或 DEMO_API_KEY / DEMO_BASE_URL / DEMO_MODEL。
export SUPERVISOR_AUTH_TOKENS='demo-token=demo-tenant:demo-user:query_weather,query_trains,search_knowledge,memory:write'
# 共享库连接；四个变量分别覆盖地址、账号、密码和连接池大小。
export SUPERVISOR_DATABASE_URL='jdbc:postgresql://127.0.0.1:5432/supervisor'
export SUPERVISOR_DATABASE_USERNAME='supervisor'
export SUPERVISOR_DATABASE_PASSWORD='请替换为实际密码'
bash run-supervisor-server.sh
```

令牌只是本机演示认证配置，实际使用请替换。无认证映射时服务拒绝启动。默认监听 `127.0.0.1:8080`，运行库是共享 PostgreSQL；地址、账号、密码和连接池由 `SUPERVISOR_DATABASE_*` 环境变量提供，启动时自动建表。多个实例连接同一库即可共享任务状态；目标数据库需预先创建，账号需要建表权限。本地回归测试使用 H2 内存库，不读取这些配置。

发起任务，普通调用等结果，带 Prefer 则立即取得任务号：

```bash
curl 'http://127.0.0.1:8080/api/supervisor/chat'   -H 'Authorization: Bearer demo-token'   -H 'Content-Type: application/json'   -H 'Prefer: respond-async'   -d '{"requestId":"request-1","sessionId":"session-1","question":"明天从杭州到上海玩一天，看看天气和火车票，规划行程","history":[]}'
```

所有接口都需要同一认证请求头；鉴权与 `Prefer: respond-async` 由请求 filter 统一处理，接口方法不接收这些参数：

| 方法与路径 | 请求/用途 |
|---|---|
| POST `/api/supervisor/chat` | 唯一参数 `ChatParam`；发起任务，requestId 相同内容重放、不同内容 409 |
| GET `/api/supervisor/runs/{runId}` | 查询进度、结果、revision、modelCalls、deadline |
| GET `/api/supervisor/runs/{runId}/detail` | 运行证据读模型：计划、父子运行、工具配对、委派回执、逐 Agent 用量、span、压缩、召回、权限、提示词版本、检查点白名单；别名 `/runs/detail/{runId}`，两者鉴权与脱敏一致 |
| GET `/api/supervisor/config` | 当前实例脱敏配置证据：模式、超时、预算、并发与速率、多 Agent、校验开关、业务规则、认证别名；不返回令牌 |
| POST `/api/supervisor/instructions` | 唯一参数 `InstructionParam`：`{runId,instructionId,expectedRevision,action,message}`；动作 MESSAGE、SWITCH、CANCEL，省略即 MESSAGE；CANCEL 不需要 `expectedRevision` |
| GET `/api/supervisor/memories` | 获取当前用户有效偏好第一页，固定 20 条；返回下一游标 |
| GET `/api/supervisor/memories/{cursor}` | 按游标获取下一页 |
| PUT `/api/supervisor/memories/{key}` | `{value,expectedVersion,expiresAt}`；首次版本 0；键为 preferredDepartureCity 或 preferredSeat |
| DELETE `/api/supervisor/memories/{key}/{expectedVersion}` | 按路径中的版本删除偏好 |

运行详情接口只返回白名单字段：不返回 `loop:v3.3` 原始消息树、召回原文、令牌或工具完整参数；工具参数仅以 SHA-256 指纹展示。子运行回答与证据在当前身份权限不足时会整体脱敏。页面的任务详情抽屉和 43 项验收面板都按需调用这两个接口，验收快照可关联任意当前身份可见的 `runId`。

每个接口的独立处理流程图见[技术方案](技术方案.md)“接口设计”。`POST /api/supervisor/chat` 的请求/响应字段、完整链路、状态码与同步异步时间线另见 [chat接口详解](chat接口详解.md)。指令示例中预期版本必须使用 GET 的最新值：

```json
{"runId":"实际任务号","instructionId":"instruction-1","expectedRevision":1,"action":"MESSAGE","message":"改成上海后天的天气"}
```

正常业务结果为 200，追加输入和切换为 202，停止为 200；认证错误 401，无权限 403，不可访问任务 404，版本/幂等冲突 409，过大 413，容量满 429，存储不可用 503。普通同步任务失败返回 502、超时 504；查询接口返回 200 和任务实际状态。

命令行：

```bash
bash run-supervisor.sh '杭州明天的天气'
bash run-supervisor.sh '明天从杭州到上海玩一天，看看天气和火车票，规划行程'
bash run.sh
```

最后一条仍运行原乘法示例。服务包是 `target/agentscope-react-demo-boot.jar`，命令行包是 `target/agentscope-react-demo.jar`。

用户于 2026-10-30 要求此后默认真实模式。服务端与 Supervisor CLI 默认均为 live；环境中的 DEMO_API_KEY、DEMO_BASE_URL、DEMO_MODEL 分别优先，否则读取 DEEPSEEK_API_KEY、DEEPSEEK_BASE_URL、DEEPSEEK_MODEL。默认地址 https://api.deepseek.com，模型 deepseek-flash。DeepSeek 显式使用普通生成（thinking disabled），兼容当前工具回传格式。缺少凭据立即报错，不回退离线。仅在明确需要离线诊断时使用 `SUPERVISOR_MODE=offline bash run-supervisor-server.sh` 或 `bash run-supervisor.sh --offline '问题'`。CLI 显式模式参数优先于环境模式。密钥只通过环境注入，不写源码。**live 仅替换模型，业务数据仍为示例。** 2026-10-30 之前的离线验收记录不代表真实模型效果。

## 配置与尚未一致的行为

| 控制 | 当前默认配置 |
|---|---|
| 单任务树 | 60秒绝对期限，16次预留模型尝试；父子及摘要共享 |
| 单次模型/工具等待 | 最多20秒/5秒，并受剩余期限约束 |
| 重试 | 最多3次真实尝试含首次；普通已发分片不重放，摘要失败分片隔离后可重试 |
| 上下文 | 完整输入按JSON UTF-8字节估算，上限由 `supervisor.policy.context-input-limit` 配置，默认8192；默认输出1024，摘要同样计入用量。目录 Schema 注入预算仍单独为6656字节 |
| HTTP准入 | 全局8、每用户2；工具物理线程8、队列32 |
| Agent树并发 | 最多8个活动子会话，根会话不占此名额；与HTTP容量分开 |
| 速率 | 每用户每分钟10个新根任务；全局每分钟120次模型尝试、90000预留估值 |
| 保存 | 最多10000任务；任务7天、会话30天、偏好默认90天 |

运行策略使用 `supervisor.policy.*`，默认安全开启值如下：

| 配置 | 默认值 | 说明 |
|---|---|---|
| `business-rules-enabled` | `true` | 工具内业务语义校验；关闭后不执行声明的业务规则 |
| `business-max-future-days` | `30` | 允许查询的最大未来天数 |
| `business-rules` | 内置三条 | 声明式业务规则列表；按顺序匹配，未声明或空列表时回退内置规则 |
| `fact-check-enabled` | `true` | 确定性数值和日期事实校验、冲突降级 |
| `semantic-verifier-enabled` | `false` | 高风险可选 `AnswerVerifier`；开启后普通任务仍不增加模型调用 |
| `fact-high-risk-tools` | `query_weather,query_trains` | 高风险工具集合 |
| `correction-limits-enabled` | `true` | 纠错次数上限与运行内工具禁用 |
| `max-consecutive-schema-errors` | `2` | 同一工具连续 schema 错误上限 |
| `max-tool-not-found` | `2` | 工具不存在次数；第二次要求澄清，第三次失败 |
| `max-business-violations` | `2` | 同一运行业务违规上限 |
| `max-total-corrections` | `6` | 同一运行总纠错上限 |
| `multi-agent-mode` | `"ADVISORY"` | `OFF` 保持原行为，`ADVISORY` 只记录决策，`ENFORCE` 执行深度/数量硬上限 |
| `multi-agent-min-parallelism` | `2` | 低于该并行度不建议多 Agent |
| `multi-agent-max-agents` | `4` | `ENFORCE` 下允许的活动子会话数 |
| `multi-agent-max-depth` | `2` | `ENFORCE` 下允许的会话深度 |
| `multi-agent-max-token-multiplier` | `2.0` | 多 Agent 预计 token 相对单 Agent 的上限倍数 |
| `multi-agent-min-latency-saving-ms` | `500` | 非时延敏感任务要求的最小收益 |
| `multi-agent-planner-enabled` | `false` | 是否注册 `plan_agents` 计划校验工具；开启会增加一个初始工具定义 |
| `multi-agent-max-plan-tasks` | `8` | 单次计划允许的最大子任务数，范围 1 到 32 |
| `topic-segmentation-enabled` | `true` | 确定性话题切分；只影响历史装配，不删除会话 |
| `topic-recent-turns` | `6` | 判断话题时考察的历史问题数 |
| `topic-min-confidence` | `0.6` | 意图变化触发切换的最低置信度 |
| `retry-max-attempts` | `3` | 单次真实请求的最大尝试次数 |
| `retry-backoff-cap-ms` | `2000` | 指数退避上限 |
| `summary-fidelity-alert-enabled` | `true` | 摘要忠实度低于阈值时写告警事件 |
| `summary-fidelity-threshold` | `1.0` | token 级保留率阈值 |
| `summary-missing-condition-policy` | `KEEP_CONDITION_VERBATIM` | 摘要遗漏关键条件时的处理：`ALERT_ONLY` 只告警、`RETRY_ON_MISSING` 追加缺失条件重试一次、`KEEP_CONDITION_VERBATIM` 在摘要前原文补齐缺失条件、`FAIL_FAST` 立即失败并写事件 |
| `execution-limits-enabled` | `true` | 是否启用单回合总步数上限 |
| `max-steps` | `64` | 单回合模型步数上限；超过后复用 `BUDGET_EXHAUSTED`，不新增 `STEP_LIMIT` |
| `context-input-limit` | `8192` | 单次模型输入按 JSON UTF-8 字节估算的上限；压缩判断与发送前校验共用该值，可按实际模型窗口调整 |
| `loop-detection-enabled` | `true` | 是否检测同一工具同一参数的重复调用 |
| `max-repeated-tool-calls` | `3` | 相同工具和参数指纹允许执行的次数；下一次返回 `LOOP_DETECTED` 工具错误，再下一次终止本轮 |
| `agent-cycle-detection-enabled` | `true` | 是否在共享调用图上拦截 `A→B→A` 和自环 |
| `semantic-loop-detection-enabled` | `false` | 是否启用轻量语义目标相似度检测；不调用模型，开启后只提醒或要求确认 |
| `semantic-loop-window` | `12` | 保留的近期语义目标数量，范围 1 到 32 |
| `semantic-loop-threshold` | `0.85` | 字符双元组 Jaccard 相似度阈值，范围 0 到 1 |
| `semantic-loop-max-warnings` | `2` | 相似目标允许返回 `LOOP_DETECTED` 的次数，下一次转 `NEEDS_INPUT` |
| `context-recall-enabled` | `false` | 是否注册 `recall_context` 工具；开启会增加初始工具表预算占用 |

`multi-agent-mode` 在 YAML 中必须加引号，例如 `"OFF"`；YAML 1.1 会把未加引号的 `OFF` 解析成布尔 `false`。

`plan_agents` 只在 `multi-agent-planner-enabled=true` 时注册。模型提交 `goal` 和 `tasks` 后，计划器校验任务名、Agent 类型、`objective`、依赖引用和依赖环，按拓扑顺序返回 `batches`，再检查当前身份的工具权限、活动子会话数、深度和 `MultiAgentGate` 的 token / 时延门槛。结果为 `MULTI_AGENT`、`SINGLE_AGENT` 或 `REJECTED`；计划写入检查点 `plan:v1:current`，返回结果中的 `persisted` 与 `persistedKey` 表示是否已写入，不会自动创建子会话，模型仍需按批次调用 `spawn_agent`。

压缩时，`ContextCompactor` 先从历史中抽取城市、日期、席别、金额、时长、温度等关键条件，再比较模型摘要是否逐字保留。缺失条件按 `summary-missing-condition-policy` 处理；`KEEP_CONDITION_VERBATIM` 会把缺失条件原文补在摘要前，若条件块与摘要无法同时放入窗口，则写 `CONTEXT_UNCONFIRMED` 事件并放弃替换历史，不会静默丢弃条件。计划检查点存在时，摘要消息同时附带解析后的计划块。

语义循环检测在 `loop-detection-enabled` 和 `semantic-loop-detection-enabled` 同时为 `true` 时生效。工具参数按同一工具比较，协作任务跨 callee 比较，历史写入根任务共享检查点 `loop:v1:semantic-goals`。相似度达到阈值后，前 `semantic-loop-max-warnings` 次返回 `LOOP_DETECTED` 工具错误，下一次抛 `CorrectionExhausted(NEEDS_INPUT)` 暂停本轮等待用户确认；不会直接判 `FAILED`，也不调用模型。两项新能力默认关闭，原因是额外的工具定义和检测记录会改变默认 16 次根模型调用预算下的既有行为。

业务规则声明示例：

```yaml
supervisor:
  policy:
    business-rules:
      - id: DATE_IN_PAST
        tools: query_weather,query_trains
        type: DATE_NOT_BEFORE_TODAY
        field: date
        suggestion: 请改为今天或之后的日期。
      - id: SAME_CITY
        tools: query_trains
        type: FIELD_NOT_EQUAL
        field: from
        other-field: to
        suggestion: 请改为不同的出发城市和到达城市。
```

`type` 只接受 `DATE_NOT_BEFORE_TODAY`、`DATE_NOT_AFTER_DAYS`、`FIELD_NOT_EQUAL`；未知类型、空白标识或 `FIELD_NOT_EQUAL` 缺少 `other-field` 时服务拒绝启动。规则标识和字段名只进入工具错误、事件和证据，不新增对外 HTTP 错误码。

以上是服务配置，非Codex标准、实测吞吐或供应商账单。子会话独立调度与HTTP请求槽分开；子会话上限见 AgentLifecycle.MAX_ACTIVE_CHILDREN。忽略中断的阻塞调用仍占用原物理资源。

当前选择Chat Completions和local compact。Responses继续标记、远端opaque压缩、后台压缩候选、完整world-state/hook协议未接入；V2全部可选配置、resident-only列举、懒加载等也不能宣称已经一致。源码文档逐项说明差异，不能用“演示场景无需”替代核对。

默认数据只有部分示例城市与线路，离线模型只识别示例句式。live只替换模型，业务数据仍是示例。HTTP返回JSON，通过查询取进度；当前没有SSE逐字输出。没有使用外部付费模型效果来证明本地脚本验收结论。

## 阅读与验证

先读Supervisor、LoopConversation、ContextCompactor，再读RunControl、LocalAgentControl、TaskService、TaskStoreService；最后读工具、记忆、校验和repo/MyBatis。主子运行不依赖ReActAgent隐式子循环；独立校验组件仍有SDK入口。HTTP/controller、filter、service、repo、pojo等分层保持不变。

```bash
mvn -q test
mvn -q -Dtest=SupervisorSocketIT test
mvn -q -DskipTests package
```

测试与实际结果统一写入[实施记录](实施记录.md)。新核心行为重点看CoreSourceParityTest、ContextCompactionTest、AgentLifecycleTest和OfflineSourceProfileTest；事务、权限、幂等、网络传输与重启仍需对应集成验证。测试名称和源码映射不是通过记录。


## 本地前端验收页面（2026-10-30）

根路径现在由当前 Supervisor 服务提供聊天验收页。源码在 frontend/，Vue 3 + TypeScript + Vite；前端构建产物随 Boot JAR 打包。构建前端使用 `cd frontend`、`npm ci --no-audit --no-fund`、`npm run build`，再回项目目录执行 `mvn -q -DskipTests package` 并按原方式启动。只运行本机服务，未创建或发布托管站点。

页面采用会话导航、消息区与底部输入布局；支持同步与异步、按任务号刷新恢复、同会话追问、补充回答、停止和切换任务。令牌通过掩码输入临时保存在页面内存，刷新后重新输入。回答下的证据卡和任务详情展示实际响应，认证头不保存。当前标签页列表来自 sessionStorage 中的本地恢复记录，不冒充服务端会话列表。详细使用见 frontend/README.md。

前端按实际返回的 state/status 判定：任务结束时可能是 SUCCESS、NO_DATA、BUDGET_EXHAUSTED 等既有结果状态，不能只判断 DONE。现有默认分钟 token 预留额度为 90000，复合旅行规划及连续追问在本轮真实页面检查中出现 BUDGET_EXHAUSTED；页面会保留实际已完成证据，该结果不能算作行程规划成功。预算、状态机、鉴权和数据库均未因页面接入而调整。


### 页面提示连接中断时（2026-10-30 10:23 修复记录）

先检查本机 8080 是否有 Supervisor 监听。页面仍显示并不代表后端在运行；服务退出时浏览器已有 HTML 仍可显示，但 chat/get 请求会连接失败。本轮实际检查到 8080 无监听且无 Supervisor Java 进程。

此前服务依附于助手执行会话，未确保交付后持续运行。此次改用独立后台会话启动 Java 17，标准输入关闭，日志追加到 `.runtime/supervisor-server.log`，PID 写到 `.runtime/supervisor-server.pid`，认证仍从环境变量注入。启动后已从独立后续命令确认 PPID=1、进程组为自身 PID、8080 监听及首页200。此方式不添加开机自启，也不自动重启意外崩溃的服务。

原 `bash run-supervisor-server.sh` 是前台方式，使用它时应保持启动终端运行；不要把网页标签页的存在当作服务保活。未知 runId 的失败提交通过页面“恢复原请求”重放原 requestId 和请求体，不能直接生成另一个请求号。本次已实际恢复用户原“你好”请求并获得 202、随后 GET 200 成功答复。


## 页面v1.2更新（2026-11-01）

同源根路径提供重制的本地Vue验收页。左侧新增“验收场景43”与“显式偏好”；运行中可补充说明、切换或停止。详情提供原请求/指令重放与版本冲突检查。验收记录可本地导出，正文仅在页面内存；权限变化会撤下关联快照，刷新需重新配置令牌。

v1.2 历史结果见[页面验收报告-v1.2](页面验收报告-v1.2.md)。P0–P5 完成后的结果见[页面验收报告-v1.3-43项全覆盖](页面验收报告-v1.3-43项全覆盖.md)：40项通过、3项部分验证、0项失败、0项阻塞；A2、B3、E4 的剩余边界在报告中逐项写明。没有为追求通过放宽默认预算，也没有修改任务状态与权限语义。
