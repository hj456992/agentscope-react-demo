# jev接入研究

状态：研究稿，不进入本次多 Agent 平台改造。

日期：2026-10-29。

结论：可以研究接入，但不建议在本次改造中实施。等当前表结构、API、运行状态机和目录体系落地并稳定运行后，再用影子模式验证，确认收益后再决定是否接入。

## 1. 研究边界

本文只回答五个问题：

1. JEV 能不能节省 token。
2. 接在哪些位置最划算。
3. 怎么接才不会破坏当前主 Agent 架构。
4. 怎么验证它不会把用户路由错。
5. 什么条件下才进入实现。

本文不改变 `docs/技术方案.md` 的 v4.0 表结构、API、状态机和实施顺序，也不改变 `docs/多agent平台改造.md` 的总体设计。文中出现的表、接口和事件类型都是后续候选方案，不是本次改造的交付内容。

## 2. 结论先行

1. JEV 适合做封闭判断，不适合做自然语言生成。
2. 最值得接的位置是“子 Agent 路由短名单”和“是否走确定性工具”，不是所有请求都先过 JEV。
3. 查询改写、复杂行程规划、多意图拆解、澄清话术生成仍然要交给大模型或模板，JEV 不能替代。
4. 推荐顺序是影子模式、辅助路由、白名单自动路由，不能一上来就自动分流。
5. token 是否真的下降要看候选数量、回退比例和主模型提示长度，不能只看 JEV 单价。
6. JEV 的输出只能作为建议，不能作为权限、预算、审批和租户隔离的依据。

## 3. JEV 的能力边界

按公开资料，JEV 是只做判断、不生成自然语言的决策模型，常见输出是：

- `Choice`：从给定选项中选择一个。
- `Score`：给候选打分。
- `Noul`：做是或否的判断。
- 置信度：表示模型对本次判断的确定程度。

它的优势是判断延迟低、输出短；缺点是候选集必须由平台提供，候选之外的内容它选不出来，也不能生成解释、改写文本或最终回答。

| 能力 | 是否适合 | 说明 |
|---|---|---|
| 意图分类 | 适合 | 候选意图有限，属于闭集选择 |
| 子 Agent 路由 | 适合 | 从候选 Agent 中选一个，必须带“其他”和“不确定” |
| 风险预判 | 可用 | 只能提示风险，最终由策略引擎决定 |
| 是否走确定性工具 | 适合 | 天气、车次、订单状态这类固定查询 |
| 是否需要澄清 | 可用 | 只判断“缺信息”，澄清话术仍要模板或大模型生成 |
| 是否需要改写 | 可用 | 只判断“需不需要改写”，改写文本仍要生成模型 |
| 查询改写 | 不适合 | JEV 不生成自然语言 |
| 最终回答 | 不适合 | 回答需要生成能力 |
| 复杂行程规划 | 不适合 | 需要多步推理和生成 |
| 多意图拆解 | 不适合 | 单次选择无法可靠拆出多个意图，应交给主模型 |
| 权限、预算、审批 | 不适合 | 只能由策略引擎、权限表和预算表决定 |

公开资料中的 token 规律也要注意：JEV 的输入会随候选数量增长，候选很多时不一定比大模型便宜。常见优化是先用向量召回做候选短名单，再让 JEV 在短名单里选择。

## 4. 当前链路的成本在哪里

当前设计把改写、意图识别和路由都收敛到主模型。这样做链路简单，但主模型每次都要承担：

- 理解用户问题。
- 判断是否需要改写。
- 判断意图和业务域。
- 查看子 Agent 目录和工具说明。
- 决定是否委派、委派给谁。
- 组织回答或澄清问题。

当子 Agent 数量变多后，主模型提示里的目录、能力卡和工具说明会持续变长，这部分输入 token 会随平台扩张而增长。JEV 能分担的是其中“从有限选项里选一个”的判断，不是全部理解工作。

## 5. 推荐的接入位置

| 优先级 | 接入点 | JEV 负责 | 省 token 的方式 |
|---|---|---|---|
| 高 | 子 Agent 候选路由 | 从短名单中选一个，附带“其他”和“不确定” | 主模型不再看完整目录，只看短名单 |
| 高 | 确定性工具分流 | 判断是否可以直接走固定工具 | 高置信时跳过主模型调用 |
| 中 | 意图分类 | 在预定义意图中选择 | 不再让主模型生成分类文字 |
| 中 | 是否需要澄清 | 判断信息是否不足 | 减少不必要的澄清轮次 |
| 中 | 是否需要改写 | 判断是否值得调用改写模型 | 不需要改写时跳过改写调用 |
| 低 | 风险预判 | 判断是否高风险 | 低风险请求少走一次判断，但最终仍由策略引擎决定 |
| 不建议 | 查询改写、回答生成、复杂规划 | — | JEV 没有生成能力 |

## 6. 推荐链路

```text
用户问题
  ↓
规则归一化
  ↓
候选召回：向量短名单，最多十五个
  ↓
轻量决策：在候选中选择，必须包含“其他”和“不确定”
  ↓
置信度、槽位和权限检查
  ├─ 高置信、槽位齐全、允许自动路由
  │    → 直接进入子智能体或确定性工作流
  ├─ 中置信
  │    → 主模型带短名单和轻量决策提示，由主模型决定
  └─ 低置信、超时、服务异常、多意图
       → 回退到现有主模型全量路由
```

建议的初始阈值：

- 高置信：大于等于 `0.85`，且没有缺槽位，且该 Agent 明确允许自动路由。
- 中置信：`0.55` 到 `0.85`，只作为主模型提示，不作为最终决定。
- 低置信：小于 `0.55`，直接回退。
- 第一名和第二名分数差距过小，视为多意图或不确定，交给主模型。
- 候选里没有合适项时，必须允许选择“不确定”，不能强迫 JEV 在错误候选里选一个。

自动路由仍然要创建任务记录和主运行记录，只是主运行不调用大模型。这样任务状态、审计、预算、恢复和权限检查都沿用现有机制。

## 7. 平台化接入设计

### 7.1 端口与实现

不要在 `Supervisor` 主循环里写死 JEV 调用，增加一个可替换端口：

```text
SemanticDecisionPort
  ├── JevSemanticDecisionAdapter
  ├── LlmSemanticDecisionAdapter
  └── RuleSemanticDecisionAdapter
```

由配置决定某个租户、某个 Agent 使用哪个实现，以及失败后的回退顺序。JEV 是外部服务，按外部服务治理：

- 超时。
- 熔断。
- 健康检查。
- 密钥引用。
- 模型版本固定。
- 调用审计。

JEV 不可用时必须回退，不能阻塞对话。

### 7.2 Agent 路由声明

子团队仍然只声明自己的路由信息，不改主 Agent。建议在 `supervisor_agent_versions` 增加 `routing_json`：

```json
{
  "intents": ["weather.current", "weather.alert"],
  "examples": ["杭州明天天气", "上海下雨吗"],
  "negativeExamples": ["帮我订酒店"],
  "requiredSlots": ["city", "date"],
  "autoRouteAllowed": true,
  "confidenceThreshold": 0.85,
  "priority": 100
}
```

字段含义：

- `intents`：该 Agent 能处理的意图标签。
- `examples`：正例，用于候选召回。
- `negativeExamples`：负例，用于降低误路由。
- `requiredSlots`：自动路由前必须齐全的槽位。
- `autoRouteAllowed`：是否允许跳过主模型。
- `confidenceThreshold`：该 Agent 的自动路由阈值，可以高于平台默认值。
- `priority`：多个 Agent 都能处理时的优先顺序。

新 Agent 版本发布时重建候选索引。缓存键要带目录版本和服务版本，避免新 Agent 发布后仍命中旧路由。

### 7.3 候选表

后续如果接入，至少需要下面三张表。它们不进入本次改造的 Flyway 脚本。

```sql
CREATE TABLE supervisor_semantic_services (
    service_id           VARCHAR(128) PRIMARY KEY,
    tenant_id            VARCHAR(128),
    name                 VARCHAR(200) NOT NULL,
    provider_type        VARCHAR(16) NOT NULL,
    endpoint             TEXT NOT NULL,
    auth_ref             VARCHAR(200),
    model_name           VARCHAR(128) NOT NULL,
    model_version        VARCHAR(64) NOT NULL,
    timeout_ms           INT NOT NULL CHECK (timeout_ms > 0),
    max_candidates       INT NOT NULL CHECK (max_candidates > 0),
    confidence_threshold NUMERIC(4,3) NOT NULL,
    fallback_service_id  VARCHAR(128),
    owner                VARCHAR(128) NOT NULL,
    state                VARCHAR(16) NOT NULL,
    health_state         VARCHAR(16) NOT NULL,
    last_health_at       TIMESTAMPTZ,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_supervisor_semantic_provider CHECK (provider_type IN (
        'JEV', 'LLM', 'RULE'
    )),
    CONSTRAINT ck_supervisor_semantic_service_state CHECK (state IN (
        'DRAFT', 'ACTIVE', 'DISABLED'
    )),
    CONSTRAINT ck_supervisor_semantic_confidence CHECK (
        confidence_threshold >= 0 AND confidence_threshold <= 1
    )
);
```

`tenant_id` 为空表示平台共享服务；非空表示租户私有服务，标识里仍要带租户命名空间。

```sql
CREATE TABLE supervisor_semantic_service_versions (
    service_id      VARCHAR(128) NOT NULL
                    REFERENCES supervisor_semantic_services(service_id),
    version         INT NOT NULL,
    config_json     JSONB NOT NULL,
    config_checksum CHAR(64) NOT NULL,
    state           VARCHAR(16) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    activated_at    TIMESTAMPTZ,
    PRIMARY KEY (service_id, version),
    CONSTRAINT ck_supervisor_semantic_version_state CHECK (state IN (
        'DRAFT', 'CANARY', 'ACTIVE', 'RETIRED'
    ))
);
```

`config_json` 保存候选上限、阈值、输出字段映射和回退策略，不保存密钥。

```sql
CREATE TABLE supervisor_semantic_predictions (
    prediction_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id           VARCHAR(128) NOT NULL,
    run_id              VARCHAR(64) NOT NULL
                        REFERENCES supervisor_agent_runs(run_id),
    stage               VARCHAR(16) NOT NULL,
    service_id          VARCHAR(128),
    service_version     INT,
    input_hash          CHAR(64) NOT NULL,
    candidate_count     INT NOT NULL,
    candidate_hash      CHAR(64) NOT NULL,
    choice_id           VARCHAR(128),
    confidence          NUMERIC(4,3),
    alternatives_json   JSONB,
    latency_ms          INT,
    fallback_used       BOOLEAN NOT NULL DEFAULT FALSE,
    outcome             VARCHAR(16) NOT NULL,
    error_code          VARCHAR(64),
    source_message_seq  BIGINT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_supervisor_semantic_stage CHECK (stage IN (
        'INTENT', 'ROUTE', 'RISK', 'CLARIFY', 'TOOL'
    )),
    CONSTRAINT ck_supervisor_semantic_outcome CHECK (outcome IN (
        'ACCEPTED', 'OVERRIDDEN', 'REJECTED', 'FAILED'
    )),
    CONSTRAINT ck_supervisor_semantic_prediction_confidence CHECK (
        confidence IS NULL OR (confidence >= 0 AND confidence <= 1)
    )
);

CREATE INDEX idx_supervisor_semantic_prediction_tenant
    ON supervisor_semantic_predictions (tenant_id, created_at DESC);

CREATE INDEX idx_supervisor_semantic_prediction_run
    ON supervisor_semantic_predictions (run_id, stage);
```

明细表不保存完整用户原文，只保存输入摘要和消息序号。需要复盘时按消息序号回到消息表，按权限读取。

候选召回还需要一份路由样例。第一阶段可以直接从 `routing_json` 的 `examples` 建进程内索引；当 Agent 数量和样例数量增长到一定程度，再增加独立的路由样例表和向量索引。不要为了接入 JEV 提前复制一套目录事实。

### 7.4 事件类型

后续接入时，在 `supervisor_run_events` 增加：

- `SEMANTIC_DECIDED`
- `ROUTE_SELECTED`
- `SEMANTIC_FALLBACK`
- `SEMANTIC_CACHE_HIT`

事件负载只放选择结果、置信度、候选数量、耗时和回退原因，不放完整用户原文和完整候选说明。

### 7.5 管理接口

后续接入时增加管理接口，不增加用户接口。

```text
POST /api/platform/admin/semantic-services
PUT  /api/platform/admin/semantic-services/{serviceId}
POST /api/platform/admin/semantic-services/versions
PUT  /api/platform/admin/semantic-services/{serviceId}/versions/{version}/activation
POST /api/platform/admin/semantic-services/health-checks
GET  /api/platform/admin/semantic-services
GET  /api/platform/admin/semantic-services/{cursor}
GET  /api/platform/admin/semantic-services/detail/{serviceId}

POST /api/platform/admin/semantic-evaluations
GET  /api/platform/admin/runs/detail/{runId}/decisions
```

`POST /semantic-evaluations` 用于对比不同服务版本在同一个评测集上的路由准确率、回退率和 token 成本，不直接改变线上路由。

用户接口保持不变。客户端不能自己指定意图或强制路由，身份、租户和权限仍然只来自令牌。

## 8. token 账怎么算

### 8.1 节省来源

JEV 带来的节省主要有四项：

1. 主模型不再读完整子 Agent 目录，只读候选短名单。
2. 简单确定性查询完全跳过主模型。
3. 减少不必要的澄清轮次。
4. 减少不必要的改写调用。

JEV 本身的成本也要算：

- JEV 输入 token。
- 候选召回和向量化成本。
- 额外网络延迟。
- 失败回退时的重复调用。

### 8.2 什么时候可能不划算

- 候选很多，又没做短名单，JEV 输入会变长。
- 用户问题很复杂，JEV 判断完仍然要主模型完整处理。
- 多意图请求，JEV 只能选一个，最后还要主模型拆解。
- 回退比例很高，等于多付一次判断成本。
- 错路由导致重新执行，成本比直接交给主模型更高。

### 8.3 公开资料的量级

公开资料给出的 JEV 价格和延迟量级，以及候选短名单的对比数据，只能作为研究参考，不能直接当成接入后的真实收益。接入前必须用自己的业务评测集复测。

公开基准中的一个例子：

- 全部 77 个意图：约 1101 输入 token、828 输出 token、319 毫秒。
- 先做前 15 个候选：约 513 输入 token、178 输出 token、408 毫秒。
- 同场景大模型：约 607 输入 token、228 输出 token、2795 毫秒。

短名单能明显减少输入输出，但正确意图不在短名单里时，JEV 无法补救。所以候选召回率本身也是验收指标。

## 9. 质量和安全边界

| 风险 | 处理方式 |
|---|---|
| 候选漏选 | 短名单召回率单独评测；保留“不确定”选项；低置信回退 |
| 强行选择 | 候选必须包含“其他”和“不确定”，不能只给有限选项 |
| 置信度不等于正确率 | 用真实评测集做校准，按意图分别设阈值 |
| 多意图请求 | 分数接近或候选冲突时直接交给主模型 |
| 新 Agent 未进索引 | Agent 版本发布时重建索引；缓存键带目录版本 |
| 语言和领域漂移 | 按租户、语言、业务域分层评测，不混算总体准确率 |
| 服务故障 | 超时、熔断、回退；JEV 不能阻塞对话 |
| 用户隐私 | 只发当前问题和候选标签，不发完整历史；日志存摘要和消息序号 |
| 提示注入 | JEV 输出视为不可信数据，做结构校验，不执行其中任何指令 |
| 权限提升 | JEV 不能设置租户、用户、权限、预算和审批结果 |
| 成本倒挂 | 先影子模式计算综合成本，再决定是否自动路由 |
| 供应商锁定 | 端口加适配器，固定模型版本，保留替换实现 |

JEV 的输出是建议，不是授权。所有工具调用仍然要经过权限检查、参数校验、预算检查和副作用账本；所有审批仍然由审批表和处理人决定。

## 10. 评测与灰度

### 10.1 三个阶段

1. 影子模式：JEV 正常判断并记录，但不影响真实路由。对比 JEV 选择和大模型选择，统计一致率和错路由率。
2. 辅助模式：JEV 只提供候选短名单和提示，主模型仍做最终决定。观察主模型 token 是否真的下降。
3. 白名单自动路由：只对少量高置信、结果确定的意图开放，例如天气、车次、订单状态。出现异常立即回退主模型。

### 10.2 评测集

评测集至少覆盖：

- 每个业务域的正例。
- 容易混淆的负例。
- 多意图请求。
- 长问题、短问题、口语化问题。
- 新上线 Agent 的样例。
- 租户私有 Agent 的样例。
- 恶意输入和提示注入样例。

### 10.3 指标

| 指标 | 说明 |
|---|---|
| 首选准确率 | JEV 第一选择是否正确 |
| 前 k 召回率 | 正确 Agent 是否在候选短名单里 |
| 错路由率 | 自动路由到错误 Agent 的比例 |
| 回退率 | 低置信、异常、多意图导致回退的比例 |
| 主模型调用下降比例 | 自动路由节省的调用次数 |
| 输入 token 下降比例 | 主模型提示缩短的幅度 |
| 输出 token 下降比例 | 主模型生成减少的幅度 |
| 综合成本 | JEV、向量化、主模型、重试的总成本 |
| 额外延迟 | 影子模式和自动路由相对当前链路的 p95 延迟 |
| 审计完整率 | 每次判断是否有选择、置信度、版本和结果 |

### 10.4 建议的自动路由门槛

下面只是初始建议，必须用真实数据校准：

- 白名单意图的错路由率低于 `0.5%`。
- 正确 Agent 的短名单召回率高于 `99%`。
- 回退率低于 `20%`。
- 主模型调用次数下降 `20%` 以上。
- 综合成本下降 `15%` 以上。
- p95 额外延迟不超过 `500` 毫秒。
- 任何一项不达标，不进入自动路由。

自动路由必须支持一键关闭，按租户、按 Agent、按意图分别关闭。

## 11. 后续实施顺序

前提：当前 `docs/技术方案.md` 的 v4.0 表结构、API、运行状态机和目录体系已经落地并稳定运行。

1. 固化 JEV 接口协议、部署方式、超时、价格、数据留存和区域要求。
2. 建立路由评测集，先测大模型当前路由准确率，作为基线。
3. 增加 `SemanticDecisionPort` 和影子模式，只记录，不改变路由。
4. 补 `routing_json`、候选召回和服务登记表，做辅助模式。
5. 选择三到五个确定性意图做白名单自动路由。
6. 按租户和 Agent 灰度，保留回退和一键关闭。
7. 评测达标后再扩大范围，不达标就停在辅助模式。

本次改造不执行以上任何一步，也不新增 `supervisor_semantic_*` 表。

## 12. 待确认问题

1. JEV 是云端接口还是可私有部署，输入数据会不会被留存。
2. JEV 的接口协议、候选上限、批量能力、多标签能力和超时限制。
3. JEV 是否支持中文、多语言和领域自定义候选。
4. 置信度如何校准，是否需要平台自己做温度缩放或阈值拟合。
5. 哪些意图允许自动路由，哪些必须经过主模型。
6. 错路由的业务容忍度是多少，是否需要人工复核。
7. 路由样例由子团队维护还是平台统一维护。
8. 评测集由谁标注、多久更新一次。
9. JEV 服务故障时的回退顺序和告警等级。
10. 是否允许按租户使用不同 JEV 版本和阈值。

## 13. 决策记录

| 项目 | 结论 |
|---|---|
| 是否本次接入 | 否 |
| 是否保留研究 | 是，本文作为后续评审依据 |
| 是否修改当前表结构 | 否 |
| 是否修改当前 API | 否 |
| 是否修改当前状态机 | 否 |
| 重新评审条件 | 当前改造完成、目录稳定、评测集就绪、JEV 协议和成本确认 |
| 首选试点 | 子 Agent 路由短名单和确定性工具分流 |
| 禁止用途 | 生成回答、生成改写、决定权限和审批 |

## 14. 参考资料

以下资料来自公开网络，未做供应商环境实测。价格、延迟和准确率数据只作为研究参考，接入前必须用真实业务评测集复测。

- [JEV模型最新研讨：只下判断不说话，低延时才是被低估的变量](https://developer.aliyun.com/article/1765496)
- [从判断到回复：Jev 与大模型在微信场景里的分工实战](https://cloud.tencent.cn/developer/article/2750840?policyId=1004)
- [decisionModelJEV：intent classification benchmark](https://github.com/PrasanthiDesiraju/decisionModelJEV)
