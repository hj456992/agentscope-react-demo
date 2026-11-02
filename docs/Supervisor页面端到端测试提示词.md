# Supervisor 页面端到端测试提示词

用途：把下面代码块中的整段内容直接发给 Codex，让它在指定目录检查环境、启动 Supervisor 服务、用真实页面测试，并在发现问题后修复和复测。

边界：不新增单元测试，不以测试用例或 curl 代替页面验证；不新增对外 HTTP 错误码、`Status` 枚举值或数据库表字段；不提交 commit，不 push。

```text
你在 /Users/hou/Documents/Codex/2026-09-22/garden-product-design/outputs/demo/demo 工作。

这是一个 Supervisor Spring Boot 演示应用，Java 17 + Maven，默认端口 8080。不是 outputs/demo 下的爱聊主应用，也不是 outputs/demo/web。当前任务是不写单元测试、不新增测试用例，直接用真实页面做端到端检查；发现问题后修复，再重启页面复测。

一、先检查再启动

1. 阅读 CLAUDE.md、AGENTS.md、docs/Supervisor使用说明.md、docs/chat接口详解.md。
2. 检查 Java 17、Maven、8080 端口和残留 Java 进程；不要覆盖用户未提交的改动。
3. 构建：
   cd /Users/hou/Documents/Codex/2026-09-22/garden-product-design/outputs/demo/demo
   mvn -q -DskipTests package
4. 启动：
   export SUPERVISOR_AUTH_TOKENS='demo-token=demo-tenant:demo-user:query_weather,query_trains,search_knowledge,memory:write'
   bash run-supervisor-server.sh
5. 等启动日志出现后，先用 curl 确认接口可用：
   curl -i 'http://127.0.0.1:8080/api/supervisor/runs/not-exist' -H 'Authorization: Bearer demo-token'
   期望不是连接失败；未认证时应为 401。

二、打开页面

使用浏览器当前已打开的 Supervisor 聊天页面。若没有打开，访问 http://127.0.0.1:8080/ 并确认页面可加载。
不要自行新建前端页面；如果根路径没有页面，记录为阻塞并停止页面测试，同时报告页面实际调用的接口地址和请求格式。

页面需要认证时使用 Authorization: Bearer demo-token，令牌只放在浏览器请求头或环境变量中，不写入源码和截图。

三、页面测试场景

1. 页面加载
   - 确认无白屏、无未捕获异常。
   - 记录 Console 错误、失败的网络请求和 HTTP 状态码。

2. 普通同步对话
   - 输入：明天从杭州到上海玩一天，看看天气和火车票，规划行程
   - 确认提交后有加载或禁用状态，不能重复提交。
   - 确认最终回答可见，天气/车票证据和 Agent 信息正常展示。
   - 页面不能直接显示堆栈、内部错误码或未经处理的原始 JSON。

3. 异步任务
   - 如果页面支持异步，提交后确认立即返回任务号，并能轮询到完成。
   - 任务运行中刷新页面，确认任务仍可恢复或查询。

4. 同一会话追问
   - 继续输入：那后天呢
   - 确认沿用同一 sessionId，回答能承接上一轮的城市和日期条件。

5. 切换话题
   - 输入：帮我看看杭州到成都的火车票
   - 确认页面不报错，旧话题条件不会错误带入新话题。

6. 业务参数错误
   - 输入：查一下昨天的杭州天气
   - 输入：杭州到杭州的火车票
   - 确认页面展示可理解的修正提示或澄清问题，不出现 500、空白回答、堆栈。

7. 输入边界
   - 提交空问题和超长问题。
   - 确认页面有前端校验或可读错误，请求不会卡死。

8. 运行中切换或取消
   - 如果页面提供取消，运行中取消一次，确认页面停止等待且迟到结果不覆盖当前状态。

四、发现问题后的修复流程

1. 每个失败场景先记录：页面操作、实际请求、响应状态、响应内容、Console/Network 信息。
2. 定位是前端展示、请求参数、认证、后端 HTTP 映射还是 Supervisor 业务逻辑问题。
3. 只做最小修复，保持现有约束：
   - 不新增对外 HTTP 错误码；
   - 不新增 Status 枚举值；
   - 不新增数据库表字段；
   - 不削弱鉴权、预算、幂等和证据权限检查；
   - 所有 if 块使用花括号。
4. 修改后重新构建并重启服务，再打开页面重跑失败场景和相邻场景。
5. 不要用单元测试、mock 或仅 curl 代替页面验证；页面无法操作时明确报告阻塞原因。

五、最终报告

按场景编号输出：通过/失败、实际页面表现、Console/Network 关键信息、根因、修改文件、复测结果。
失败场景给出最小复现步骤；没有实际观察到的项目不要标记为通过。
不要提交 commit，不要 push。
```
