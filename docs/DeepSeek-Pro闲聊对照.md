# DeepSeek Pro 普通闲聊页面对照

2026-11-01。用户要求用deepseek-pro重测你好、吃了吗、你叫什么名字。

## 模型核验与实验条件

供应商真实GET /models返回deepseek-flash、deepseek-v4-pro。直接请求deepseek-pro真实返回HTTP400：支持名称为deepseek-flash、deepseek-v4-pro。已向用户明确说明正式名称后，使用deepseek-v4-pro，不把无效名称声称为已执行模型。

本次在独立8081服务、独立H2 .runtime/deepseek-pro-casual运行同一JAR和同一套页面；进程环境DEMO_MODEL=deepseek-v4-pro，SUPERVISOR_MODE=live。保持原提示词、工具权限、预算和关闭思考设置（thinking.type=disabled）。默认8080服务与原数据库未重启或切换模型。新会话连续发送三句，与上轮发送顺序相同；三轮同sessionId=da897615-76ed-4ad9-90d5-4d640c3f4d39。

## 实际回复

| 输入 | Pro页面实际原文 | 与上一轮样本相比 |
|---|---|---|
| 你好 | 你好！我是你的行程助理，可以帮你查询天气、火车票，或者参考行程安排资料。有什么需要帮忙的吗？ | 能接住问候，仍主动介绍旅行能力。 |
| 吃了吗 | 哈哈，我是不用吃饭的，谢谢关心～ 你吃了没？😄 需要我帮你安排点什么吗？比如查天气、看火车票，或者规划行程？ | 本次没有出现上一轮“我吃过了”再说“不用吃饭”的矛盾。 |
| 你叫什么名字 | 你好！我是你的行程助理，可以帮你查天气、看火车票、参考行程资料。我本身没有固定的名字，你可以随便叫我，比如“小助手”就行 😊 有什么需要我帮忙的吗？ | 仍不使用页面Supervisor名称，身份名称没有统一。 |

## 真实HTTP与页面记录

三次POST /api/supervisor/chat均202，随后GET /api/supervisor/runs/{runId}均200；三轮终态SUCCESS，各1次模型调用；无返回的工具证据/执行事件。本次读取Console warn/error=[]。页面仍将闲聊显示为“查询成功”，换模型没有改变这个前端文案。

| 输入 | requestId | runId |
|---|---|---|
| 你好 | 42c65ec7-8d8a-4d75-86d2-98a57cd062b6 | 2f085d86-9489-4a02-8dda-a7c1dcb5bc5c |
| 吃了吗 | caa5e22d-9cba-4824-b185-12ddf19a14eb | 822185b0-933e-4881-98b0-ef12fc963994 |
| 你叫什么名字 | 4640b41f-768f-4c68-bd20-d6fa80675331 | 8e22d757-1995-4287-baba-dfc3f408d86b |

## 结论与边界

本次Pro样本在吃饭问题上比上一轮更一致，但仍会在每轮附带旅行介绍，未统一名字，闲聊标签也未改变。每种模型仅该三轮对话，不构成总体模型质量或稳定性胜率结论，不能说永久修复幻觉。没有修改生产源码、默认模型或提示词；未新增单元测试、mock、commit或push。实验完成后停止8081独立进程并关闭临时标签，8080原服务保留。

证据：Supervisor-deepseek-v4-pro-页面观察.json、Supervisor-deepseek-v4-pro-实际回复.jpg、Supervisor-deepseek-v4-pro-运行配置.json、Supervisor-deepseek-pro-名称核验.json。凭据仅环境/请求头/页面内存，没有写入这些交付记录。
