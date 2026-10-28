# AgentScope ReActAgent 最小 Java 示例

Java 17 + Maven + AgentScope Java 2.0.1，与当前 DSH 项目使用的 SDK 版本一致。包含原命令行示例和独立的 Supervisor Spring Boot 服务。

目录位于爱聊 Git 项目的 `demo/` 下，已通过该仓库 `.git/info/exclude` 中的 `/demo/` 排除，源码和构建结果均不提交。此规则仅对本机仓库生效。

## Supervisor 与三个子 Agent 服务

`dev.garden.demo.supervisor` 已全包复核改造为统一模型—工具循环：用户原话直接进入 Supervisor 模型，模型自行选择天气、火车票或行程规划 Agent，工具结果写回同一会话继续。提供 Spring Boot HTTP 服务、持久任务、执行中追加指令、重启恢复、预算、工具校验、会话记忆和证据核对。

```sh
mvn -q package
export SUPERVISOR_AUTH_TOKENS='demo-token=demo-tenant:demo-user:query_weather,query_trains,search_knowledge,memory:write'
bash run-supervisor-server.sh
```

默认监听 `127.0.0.1:8080`，使用本地 H2、离线模型与示例数据。令牌需自行替换。启动后直接打开 <http://127.0.0.1:8080/> 使用内置测试页面：可发起任务、查看状态/证据/事件、提交执行中的新指令、按 runId 查询任务、维护长期偏好，并查看每条请求的状态码与原始响应。真实本地 HTTP 与强制退出恢复均有自动化验收，结果见实施记录。

- [完整实现说明：16 项处理、流程图、接口和运行方式](docs/Supervisor使用说明.md)
- [固定 Codex / Claude 源码映射及适配差异](docs/源码参考与适配边界.md)
- [已批准技术方案](docs/技术方案.md)
- [验收用例](docs/验收用例.md) · [执行结果](docs/实施记录.md)

CLI 仍可运行 `bash run-supervisor.sh --offline '杭州明天的天气'`。源码和构建结果保留本地，不更改父仓库的忽略策略。下面是原乘法示例说明。

## 先跑起来

在本目录执行（Java 17 或更高版本，Maven 3.9）：

```sh
mvn -q package
bash run.sh
```

如果本机 Maven 尚未加入 PATH，可用 `/Users/hou/.local/apache-maven-3.9.16/bin/mvn -q package`。

默认是离线模式，不需要 Key，不调用外部模型。首次 Maven 构建可能需要下载依赖。预期主要输出：

```text
模式：离线脚本模型（真实 AgentScope 工具循环，无网络请求）
用户：请调用乘法工具计算 128 × 36，并用中文回答。
[模型调用 1 / 模拟] 返回工具调用 multiply(128, 36)
[工具执行] multiply(a=128, b=36) → 4608
[模型调用 2 / 模拟] 读到工具结果 4608，返回最终答案
Agent：128 × 36 = 4608。
验证通过：2 次模型调用 → 1 次工具执行 → 工具结果进入第二次模型输入。
```

离线脚本只支持这个固定算例。它验证真实 SDK 的工具注册、参数绑定、工具执行、结果回传与循环终止；不能证明真实模型的工具选择能力。

## 接真实模型

使用支持工具调用的兼容 Chat Completions 服务，配置供应商提供的地址、模型名与 Key：

```sh
# 在当前终端设置；不要把真实 Key 写进源码或提交记录。
export DEMO_BASE_URL='供应商提供的兼容接口基础地址'
export DEMO_MODEL='支持工具调用的模型名称'
```

在 macOS 默认 zsh 中隐藏输入 Key：

```zsh
read -rs 'DEMO_API_KEY?请输入 API Key: '; echo
export DEMO_API_KEY
bash run.sh --live
# 也可更换问题
bash run.sh --live '用工具计算 37 乘以 19'
unset DEMO_API_KEY
```

`DEMO_BASE_URL` 使用基础地址，不包含 `/chat/completions`，具体取值以供应商文档为准。显式 `--live` 才会发起外部模型请求；缺少配置立即报错。示例未预置供应商或账户凭据。

`run.sh` 固定使用 UTF-8 输出中文。也可直接运行 `java -Dfile.encoding=UTF-8 -jar target/agentscope-react-demo.jar`。SDK 若提示未找到 SLF4J provider，不影响示例的控制台输出。

## 代码怎么读

先读 `src/main/java/dev/garden/demo/ReActAgentDemo.java`：

1. `MathTools.multiply`：实际执行计算的 Java 函数，`@Tool` 和 `@ToolParam` 描述能力与参数。
2. `Toolkit.registerTool`：把函数注册为 Agent 可调用的工具。
3. `ReActAgent.builder`：配置提示词、模型、工具和最多 4 次循环迭代。
4. `agent.call(input)`：返回 `Mono<Msg>`，框架根据模型响应执行工具并把结果送回模型。
5. 模型返回普通答案时结束循环；CLI 使用 60 秒等待超时。

`ScriptedModel.java` 仅负责无 Key 演示，可以跳过它直接看真实模型接入。真实模式的工具选择由模型完成，应用没有手写一个“先算乘法再回答”的固定编排。

```text
用户问题 → ReActAgent → 模型
                        ↓ tool call（工具名 + 参数）
              Toolkit → Java 方法
                        ↓ tool result
              ReActAgent → 模型 → 最终回答
```

Function Calling 是模型表达工具调用的机制；ReActAgent 在这个机制之上管理反复调用、结果反馈和终止。固定 Workflow 的步骤顺序通常由代码预先编排。

这里的 `ReAct` 是 Reasoning + Acting，与前端 React 无关。演示日志展示工具调用及其结果，不要求或展示模型的私有思维链。

`block` 只用于 CLI 入口，不应照搬到 WebFlux 事件线程；此示例没有 SSE、会话持久化或集群取消链路。关闭全局 HTTP 传输也只发生在独立 CLI 退出时。

参考：[AgentScope Java Agent 文档](https://github.com/agentscope-ai/agentscope-java/blob/main/docs/v2/en/docs/building-blocks/agent.md)、[工具文档](https://github.com/agentscope-ai/agentscope-java/blob/main/docs/v2/zh/docs/building-blocks/tool.md)。本示例 API 以本机 2.0.1 源码包及实际构建结果为准。
