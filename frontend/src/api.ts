import type { Trace } from "./types";

export class ApiError extends Error {
  constructor(
    public status: number | null,
    message: string,
    public retryAfterMs = 0,
    public code = "",
  ) {
    super(message);
  }
}

const messages: Record<number, string> = {
  400: "请求内容未通过校验，请检查问题和会话信息。",
  401: "认证未通过，请重新配置有效的访问令牌。",
  403: "当前身份没有查看该任务或证据的权限。",
  404: "任务不存在，或不属于当前身份。",
  409: "任务状态或版本已变化，请查询最新状态后重试。",
  413: "请求内容过长，请缩短问题或补充内容。",
  429: "当前请求较多，请稍后再试。",
  503: "任务存储暂不可用，请稍后恢复查询。",
};

/** 认证仅存在于调用栈与请求头；追踪记录从不保存请求头或原始响应。 */
export async function request<T>(
  path: string,
  token: string,
  options: {
    method?: string;
    body?: unknown;
    async?: boolean;
    signal: AbortSignal;
    timeout?: number;
  },
  trace: (value: Trace) => void,
): Promise<T> {
  const controller = new AbortController();
  const abort = () => controller.abort();
  options.signal.addEventListener("abort", abort, { once: true });
  if (options.signal.aborted) {
    controller.abort();
  }
  const timer = window.setTimeout(abort, options.timeout ?? 10000);
  const start = performance.now();
  let status: number | null = null;
  try {
    const headers: Record<string, string> = {
      Authorization: `Bearer ${token}`,
    };
    if (options.body !== undefined) {
      headers["Content-Type"] = "application/json";
    }
    if (options.async) {
      headers.Prefer = "respond-async";
    }
    const response = await fetch(path, {
      method: options.method ?? "GET",
      headers,
      signal: controller.signal,
      cache: "no-store",
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
    });
    status = response.status;
    if (status === 204) {
      return undefined as T;
    }
    // 部分成功响应（如提示词绑定发布）是空正文的 200，不应按解析失败处理。
    const text = await response.text();
    if (response.ok && text.length === 0) {
      return undefined as T;
    }
    let value: unknown;
    try {
      value = JSON.parse(text) as unknown;
    } catch {
      throw new ApiError(
        status,
        "服务返回了无法识别的内容，请检查服务和请求地址。",
      );
    }
    // 同步任务的 502/504 响应仍是完整 RunRes，需要展示已有结果与证据。
    if (!response.ok && !((status === 502 || status === 504) && isRun(value))) {
      const code =
        value && typeof value === "object" && "code" in value
          ? String((value as { code?: unknown }).code ?? "")
          : "";
      throw new ApiError(
        status,
        messages[status] ?? "服务暂时无法处理，请稍后重试。",
        retryAfter(response.headers.get("Retry-After")),
        code,
      );
    }
    return value as T;
  } catch (error) {
    if (error instanceof ApiError) {
      throw error;
    }
    throw new ApiError(
      null,
      "连接中断或等待超时，任务可能仍在执行，请恢复查询确认结果。",
    );
  } finally {
    clearTimeout(timer);
    options.signal.removeEventListener("abort", abort);
    trace({
      method: options.method ?? "GET",
      path,
      status,
      ms: Math.round(performance.now() - start),
      time: new Date().toLocaleTimeString("zh-CN", { hour12: false }),
    });
  }
}

function retryAfter(value: string | null): number {
  if (!value) {
    return 0;
  }
  const seconds = Number(value);
  const delay = Number.isFinite(seconds)
    ? seconds * 1000
    : Date.parse(value) - Date.now();
  return Number.isFinite(delay) ? Math.max(0, Math.min(delay, 60000)) : 0;
}

/** 运行详情路径；任务编号只由服务端生成，前端仍做一次路径编码。 */
export function runDetailPath(runId: string): string {
  return `/api/supervisor/runs/${encodeURIComponent(runId)}/detail`;
}

/** 任务快照路径，用于按任务编号补录验收证据。 */
export function runPath(runId: string): string {
  return `/api/supervisor/runs/${encodeURIComponent(runId)}`;
}

/** 脱敏配置证据路径。 */
export function configPath(): string {
  return "/api/supervisor/config";
}

export function isRun(value: unknown): value is import("./types").Run {
  if (!value || typeof value !== "object") {
    return false;
  }
  const run = value as Partial<import("./types").Run>;
  return (
    typeof run.runId === "string" &&
    typeof run.state === "string" &&
    typeof run.revision === "number"
  );
}
