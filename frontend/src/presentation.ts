import MarkdownIt from "markdown-it";

const markdown = new MarkdownIt({ html: false, breaks: true, linkify: false });
markdown.validateLink = (url: string) => /^https?:\/\//i.test(url);
const originalLink = markdown.renderer.rules.link_open;
markdown.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  tokens[idx]!.attrSet("target", "_blank");
  tokens[idx]!.attrSet("rel", "noopener noreferrer");
  return originalLink
    ? originalLink(tokens, idx, options, env, self)
    : self.renderToken(tokens, idx, options);
};
// 不从模型文本加载远程图片；保留图片描述，避免外部资源请求泄露页面信息。
markdown.renderer.rules.image = (tokens, idx) =>
  markdown.utils.escapeHtml(tokens[idx]?.content || "图片");

const replacements: Record<string, string> = {
  PERMISSION_DENIED: "当前身份无权执行",
  INPUT_INVALID: "输入条件需要调整",
  INVALID_INPUT: "查询条件需要调整",
  SUCCESS: "查询成功",
  NO_DATA: "暂无数据",
  DATE_IN_PAST: "查询日期早于允许范围",
  DATE_TOO_FAR: "查询日期超出未来窗口",
  SAME_CITY: "出发地和到达地不能相同",
  DENIED: "当前身份无权执行",
  FORBIDDEN: "当前身份无权执行",
  LOOP_DETECTED: "检测到重复查询或协作目标",
  INVALID_SCHEMA: "查询参数需要调整",
  BUDGET_EXHAUSTED: "本轮执行已达到限制",
  QUOTA_EXHAUSTED: "服务调用额度已用尽",
  RATE_LIMITED: "服务暂时限流",
  BUSINESS_RULE_VIOLATION: "查询条件不符合规则",
  TOOL_NOT_FOUND: "查询能力暂不可用",
  TOOL_DISABLED: "该查询暂不可用",
  CORRECTION_EXHAUSTED: "请调整查询条件",
  FACT_CONFLICT: "信息存在冲突",
  FACT_UNSUPPORTED: "信息缺少证据",
  MULTI_AGENT_LIMIT: "协作资源暂不可用",
  SUMMARY_FIDELITY_MISSING: "上下文信息需要确认",
};
export function readable(text: unknown): string {
  if (typeof text !== "string") {
    return "";
  }
  let safe = text
    .replace(
      /\b(?:[\w$]+\.)+[\w$]+(?:Exception|Error)\b[^\n]*/g,
      "处理过程中发生异常，请稍后重试。",
    )
    .replace(/^\s*(?:at\s+[\w.$]+\([^\n]*|Caused by:[^\n]*)$/gm, "");
  for (const [code, label] of Object.entries(replacements)) {
    safe = safe.replaceAll(code, label);
  }
  return safe.replace(/错误码/g, "处理提示");
}
export function renderAnswer(text: string): string {
  return markdown.render(readable(text));
}
export const labels: Record<string, string> = {
  RUNNING: "处理中",
  NEEDS_INPUT: "等待补充",
  DONE: "已完成",
  SUCCESS: "查询成功",
  PARTIAL: "部分完成",
  NO_DATA: "暂无数据",
  INVALID_INPUT: "请调整条件",
  FAILED: "执行失败",
  TIMEOUT: "执行超时",
  CANCELLED: "已停止",
  REPLACED: "已切换",
  BUDGET_EXHAUSTED: "本轮执行达到限制",
  CONTEXT_LIMIT: "上下文过长",
  RESULT_UNKNOWN: "结果待确认",
  UNKNOWN_INTENT: "需要明确查询事项",
};
export const agentName = (name: string) =>
  ({
    weather_agent: "天气助手",
    train_agent: "车票助手",
    itinerary_agent: "行程助手",
    supervisor: "Supervisor",
  })[name] ?? name;
export const toolName = (name: string) =>
  ({
    query_weather: "天气",
    query_trains: "火车票",
    search_knowledge: "知识资料",
  })[name] ?? "查询资料";
