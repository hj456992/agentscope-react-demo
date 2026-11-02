import { computed, reactive, ref } from "vue";
import {
  ApiError,
  configPath,
  isRun,
  request,
  runDetailPath,
  runPath,
} from "./api";
import type {
  Action,
  ChatPayload,
  ConfigEvidence,
  Instruction,
  InstructionPayload,
  Mode,
  Run,
  RunDetail,
  Session,
  Trace,
  Turn,
} from "./types";

const STORAGE = "supervisor.acceptance.v1";
export const sessions = reactive<Session[]>([]);
export const selectedId = ref("");
export const authenticated = ref(false);
export const credentialEpoch = ref(0);
export const diagnosticTraces = ref<Trace[]>([]);
export const accessInvalidation = ref({ sequence: 0, runId: "" });
function invalidateRun(runId: string) {
  accessInvalidation.value = { sequence: accessInvalidation.value.sequence + 1, runId };
}
const auxiliaryControllers = new Set<AbortController>();
export const storageWarning = ref("");
export const current = computed(() =>
  sessions.find((session) => session.id === selectedId.value),
);
export const activeTurn = computed(() => current.value?.turns.at(-1));
let token = "";
let credentialVersion = 0;
const timers = new Map<string, number>();
const controllers = new Map<string, Set<AbortController>>();

const id = () => crypto.randomUUID();
function createTurn(payload: ChatPayload, mode: Mode): Turn {
  return reactive<Turn>({
    key: id(),
    payload,
    mode,
    phase: "submitting",
    error: "",
    traces: [],
    inputs: [],
    visible: true,
    busy: false,
    paused: false,
    generation: 0,
    probes: [],
  });
}

/** 只保存恢复所需标识和请求体，不保存令牌、回答、证据或网络响应。 */
function persist() {
  try {
    sessionStorage.setItem(
      STORAGE,
      JSON.stringify(
        sessions.map((session) => ({
          id: session.id,
          turns: session.turns.map((turn) => ({
            key: turn.key,
            payload: turn.payload,
            mode: turn.mode,
            runId: turn.runId,
          })),
        })),
      ),
    );
    storageWarning.value = "";
  } catch {
    storageWarning.value =
      "浏览器未能保存恢复信息。当前对话仍可使用，刷新后可能无法恢复。";
  }
}

function load() {
  try {
    const saved: unknown = JSON.parse(sessionStorage.getItem(STORAGE) ?? "[]");
    if (!Array.isArray(saved)) {
      return;
    }
    for (const value of saved.slice(0, 50)) {
      if (
        !value ||
        typeof value.id !== "string" ||
        !Array.isArray(value.turns)
      ) {
        continue;
      }
      const turns: Turn[] = [];
      for (const entry of value.turns.slice(0, 100)) {
        const payload = entry?.payload;
        if (
          !payload ||
          payload.sessionId !== value.id ||
          typeof payload.question !== "string" ||
          payload.question.length > 2000 ||
          typeof payload.requestId !== "string"
        ) {
          continue;
        }
        const turn = createTurn(
          {
            sessionId: value.id,
            question: payload.question,
            history: [],
            requestId: payload.requestId,
            ...(typeof payload.acceptanceHint === "string"
              ? { acceptanceHint: payload.acceptanceHint }
              : {}),
          },
          entry.mode === "sync" ? "sync" : "async",
        );
        turn.runId = typeof entry.runId === "string" ? entry.runId : undefined;
        turn.phase = "restore";
        turn.visible = false;
        turns.push(turn);
      }
      sessions.push({
        id: value.id,
        title: turns.length ? "待恢复的会话" : "新对话",
        turns,
      });
    }
  } catch {
    storageWarning.value = "本标签页的恢复记录无法读取，请新建对话。";
  }
  selectedId.value = sessions.at(-1)?.id ?? "";
}

export function newSession() {
  const session: Session = { id: id(), title: "新对话", turns: [] };
  sessions.push(session);
  selectedId.value = session.id;
  persist();
}

function stopIO(turn: Turn) {
  turn.generation++;
  const timer = timers.get(turn.key);
  if (timer !== undefined) {
    clearTimeout(timer);
    timers.delete(turn.key);
  }
  for (const controller of controllers.get(turn.key) ?? []) {
    controller.abort();
  }
  controllers.delete(turn.key);
}

/**
 * 治理面板使用的只读/操作请求。
 *
 * <p>令牌只在调用栈和请求头中出现；更换令牌时统一中止尚未完成的治理请求，
 * 面板不会在身份切换后继续展示旧身份的数据。
 */
export async function governanceRequest<T>(
  path: string,
  options: { method?: string; body?: unknown } = {},
): Promise<T> {
  return governanceRequestAs<T>(path, token, options);
}

/**
 * 使用显式令牌调用治理接口，仅用于审批人令牌等单独输入的身份。
 *
 * <p>令牌不写入存储，也不进入追踪记录；请求结束即离开调用栈。
 */
export async function governanceRequestAs<T>(
  path: string,
  credential: string,
  options: { method?: string; body?: unknown } = {},
): Promise<T> {
  const controller = new AbortController();
  auxiliaryControllers.add(controller);
  try {
    return await request<T>(
      path,
      credential,
      { ...options, signal: controller.signal },
      () => {},
    );
  } finally {
    auxiliaryControllers.delete(controller);
  }
}

export function setToken(next: string) {
  credentialVersion++;
  credentialEpoch.value++;
  for (const controller of auxiliaryControllers) {
    controller.abort();
  }
  auxiliaryControllers.clear();
  diagnosticTraces.value = [];
  token = next.trim();
  authenticated.value = Boolean(token);
  for (const session of sessions) {
    session.title = session.turns.length ? "待恢复的会话" : "新对话";
    for (const turn of session.turns) {
      stopIO(turn);
      turn.result = undefined;
      turn.visible = false;
      turn.phase = "restore";
      turn.error = "";
      turn.busy = false;
      turn.inputs = [];
      turn.traces = [];
      turn.probes = [];
      turn.lastInstruction = undefined;
      turn.instructionNotice = "";
    }
  }
  if (token) {
    // 已知任务只读恢复；未知提交必须由用户主动选择重放原请求。
    for (const turn of current.value?.turns ?? []) {
      if (turn.runId) {
        void refresh(turn);
      }
    }
  }
}

export function selectSession(sessionId: string) {
  selectedId.value = sessionId;
  if (token) {
    for (const turn of current.value?.turns ?? []) {
      if (turn.phase === "restore" && turn.runId) {
        void refresh(turn);
      }
    }
  }
}

function schedule(turn: Turn, delay = 1000, failures = 0) {
  const previous = timers.get(turn.key);
  if (previous !== undefined) {
    clearTimeout(previous);
  }
  timers.set(
    turn.key,
    window.setTimeout(() => {
      timers.delete(turn.key);
      void query(turn, failures);
    }, delay),
  );
}

async function call<T>(
  turn: Turn,
  path: string,
  options: {
    method?: string;
    body?: unknown;
    async?: boolean;
    timeout?: number;
  },
): Promise<T> {
  const controller = new AbortController();
  const version = credentialVersion;
  const requestGeneration = turn.generation;
  let pending = controllers.get(turn.key);
  if (!pending) {
    pending = new Set();
    controllers.set(turn.key, pending);
  }
  pending.add(controller);
  try {
    return await request<T>(
      path,
      token,
      { ...options, signal: controller.signal },
      (trace: Trace) => {
        if (version === credentialVersion && requestGeneration === turn.generation) {
          turn.traces.push(trace);
          turn.traces = turn.traces.slice(-40);
        }
      },
    );
  } finally {
    pending.delete(controller);
  }
}

function applyRun(turn: Turn, value: unknown) {
  if (!isRun(value)) {
    throw new ApiError(null, "任务响应格式不正确，请恢复查询确认结果。");
  }
  const run = value as Run;
  turn.result = run;
  turn.runId = run.runId;
  if (!turn.payload.question && run.originalQuery) {
    turn.payload = { ...turn.payload, question: run.originalQuery, requestId: run.requestId };
  }
  turn.visible = true;
  turn.error = "";
  turn.paused = false;
  turn.phase = run.state === "RUNNING" ? "running" : "ready";
  const session = sessions.find((item) => item.id === turn.payload.sessionId);
  if (
    session &&
    (session.title === "待恢复的会话" || session.title === "新对话")
  ) {
    session.title = (run.originalQuery || turn.payload.question).slice(0, 24);
  }
  if (run.state === "REPLACED" && run.replacedBy) {
    let next = session?.turns.find((item) => item.runId === run.replacedBy);
    if (!next && session) {
      next = createTurn(
        { sessionId: session.id, question: "", history: [], requestId: id() },
        "async",
      );
      next.runId = run.replacedBy;
      next.phase = "restore";
      next.visible = false;
      session.turns.push(next);
    }
    if (next) {
      void refresh(next);
    }
  } else if (run.state === "RUNNING") {
    schedule(turn);
  }
  persist();
}

function failure(turn: Turn, error: unknown) {
  turn.error =
    error instanceof ApiError
      ? error.message
      : "暂时无法完成操作，请查询任务状态后重试。";
  if (
    error instanceof ApiError &&
    (error.status === 401 || error.status === 403 || error.status === 404)
  ) {
    const rejectedTrace = turn.traces.at(-1);
    if (error.status === 401) {
      setToken("");
      if (rejectedTrace) {
        turn.traces = [rejectedTrace];
      }
      turn.error = error.message;
    } else if (turn.runId) {
      invalidateRun(turn.runId);
    }
    turn.result = undefined;
    turn.visible = false;
    turn.phase = "error";
    turn.paused = true;
    return;
  }
  turn.phase = turn.runId ? "error" : "uncertain";
  turn.paused = true;
}

async function query(turn: Turn, failures = 0) {
  if (!token || !turn.runId) {
    return;
  }
  const generation = turn.generation;
  try {
    const run = await call<Run>(
      turn,
      `/api/supervisor/runs/${encodeURIComponent(turn.runId)}`,
      {},
    );
    if (generation !== turn.generation) {
      return;
    }
    applyRun(turn, run);
  } catch (error) {
    if (generation !== turn.generation) {
      return;
    }
    const retry =
      error instanceof ApiError &&
      (error.status === null || error.status === 429 || error.status === 503);
    failure(turn, error);
    if (retry && failures < 2) {
      schedule(
        turn,
        Math.max(
          2000 * 2 ** failures,
          error instanceof ApiError ? error.retryAfterMs : 0,
        ),
        failures + 1,
      );
    }
  }
}

export async function refresh(turn: Turn) {
  if (!token || turn.busy) {
    return;
  }
  stopIO(turn);
  turn.paused = false;
  await query(turn);
}

export async function submit(question: string, mode: Mode, acceptanceHint?: string) {
  if (!token || !question.trim() || question.length > 2000) {
    return;
  }
  if (!current.value) {
    newSession();
  }
  const session = current.value!;
  const prior = session.turns.at(-1);
  if (
    prior &&
    (prior.busy ||
      prior.phase === "submitting" ||
      prior.result?.state === "RUNNING" ||
      prior.result?.state === "NEEDS_INPUT" ||
      prior.phase === "uncertain" ||
      prior.phase === "restore")
  ) {
    return;
  }
  const hint = acceptanceHint?.trim() ?? "";
  const turn = createTurn(
    {
      sessionId: session.id,
      question: question.trim(),
      history: [],
      requestId: id(),
      ...(hint ? { acceptanceHint: hint } : {}),
    },
    mode,
  );
  session.turns.push(turn);
  session.title =
    session.title === "新对话" ? question.trim().slice(0, 24) : session.title;
  persist();
  await send(turn);
  return turn;
}

async function send(turn: Turn, recover = false) {
  if (!token || turn.busy) {
    return;
  }
  stopIO(turn);
  const generation = turn.generation;
  turn.phase = "submitting";
  turn.busy = true;
  turn.error = "";
  try {
    const run = await call<Run>(turn, "/api/supervisor/chat", {
      method: "POST",
      body: turn.payload,
      async: recover || turn.mode === "async",
      timeout: recover || turn.mode === "async" ? 10000 : 75000,
    });
    if (generation !== turn.generation) {
      return;
    }
    applyRun(turn, run);
  } catch (error) {
    if (generation !== turn.generation) {
      return;
    }
    failure(turn, error);
    // 明确的准入拒绝没有创建任务，允许用户修正后新提交。
    if (
      error instanceof ApiError &&
      error.status !== null &&
      [400, 401, 403, 409, 413, 429].includes(error.status)
    ) {
      turn.phase = "error";
    }
  } finally {
    if (generation === turn.generation) {
      turn.busy = false;
      persist();
    }
  }
}

export function recoverSubmission(turn: Turn) {
  return send(turn, true);
}

/** 面板共用的认证请求；令牌不导出，认证变化后拒绝旧响应。 */
export async function apiCall<T>(
  path: string,
  options: { method?: string; body?: unknown; async?: boolean; timeout?: number } = {},
): Promise<T> {
  if (!token) {
    throw new ApiError(401, "请先配置访问令牌。");
  }
  const epoch = credentialVersion;
  const controller = new AbortController();
  auxiliaryControllers.add(controller);
  try {
    const value = await request<T>(path, token, { ...options, signal: controller.signal }, trace => {
      if (epoch === credentialVersion) {
        diagnosticTraces.value = [...diagnosticTraces.value, trace].slice(-80);
      }
    });
    if (epoch !== credentialVersion) {
      throw new ApiError(null, "认证已变化，请重新查询。");
    }
    return value;
  } catch (error) {
    if (epoch === credentialVersion && error instanceof ApiError) {
      if (error.status === 401) {
        const rejectedTrace = diagnosticTraces.value.at(-1);
        setToken("");
        if (rejectedTrace) {
          diagnosticTraces.value = [rejectedTrace];
        }
      } else if ((error.status === 403 || error.status === 404) && path.startsWith("/api/supervisor/runs/")) {
        invalidateRun(decodeURIComponent(path.slice("/api/supervisor/runs/".length)));
      }
    }
    throw error;
  } finally {
    auxiliaryControllers.delete(controller);
  }
}

export async function instruct(turn: Turn, action: Action, message = "") {
  if (!token || !turn.runId || !turn.result || turn.busy) {
    return false;
  }
  if (action !== "CANCEL" && (!message.trim() || message.length > 2000)) {
    turn.error = "请输入1–2000字的指令内容。";
    return false;
  }
  stopIO(turn);
  const generation = turn.generation;
  // 停止不依赖版本号：页面上的版本可能已经过期，不能因此停不下来。
  const payload: InstructionPayload = {
    runId: turn.runId, instructionId: id(), action, message,
  };
  if (action !== "CANCEL") {
    payload.expectedRevision = turn.result.revision;
  }
  turn.lastInstruction = payload;
  turn.busy = true;
  turn.error = "";
  let succeeded = false;
  try {
    const response = await call<Instruction>(turn, "/api/supervisor/instructions", {
      method: "POST", body: payload,
    });
    if (generation !== turn.generation) {
      return false;
    }
    if (typeof response.runId !== "string" || typeof response.revision !== "number") {
      throw new ApiError(null, "指令响应无法识别，请查询任务状态确认。");
    }
    turn.instructionNotice = response.message;
    if (action === "MESSAGE") {
      turn.inputs.push(message);
    }
    // 完整状态、证据和替代任务只从真实GET更新，不用指令确认伪造最终回答。
    await query(turn);
    succeeded = true;
  } catch (error) {
    if (generation !== turn.generation) {
      return false;
    }
    failure(turn, error);
    if (error instanceof ApiError && (error.status === 409 || error.status === null)) {
      schedule(turn, 500);
    }
  } finally {
    if (generation === turn.generation) {
      turn.busy = false;
      persist();
    }
  }
  return succeeded;
}

/** 仅对已授权读取的当前任务做显式重放，稳定编号绝不自动重建。 */
export async function probeReplay(
  turn: Turn,
  kind: "request" | "request-conflict" | "instruction" | "instruction-conflict" | "stale",
  changedText = "",
  preparedStale?: InstructionPayload,
) {
  if (!token || !turn.result || !turn.visible || turn.busy) {
    return;
  }
  if (kind === "stale" && turn.result.revision <= 1) {
    turn.error = "当前尚无更早版本，请先实际补充或修改一次任务。";
    return;
  }
  const instruction = kind.startsWith("instruction") || kind === "stale";
  if (instruction && !turn.lastInstruction && kind !== "stale") {
    turn.error = "本页尚未保存可重放的指令。请先实际发送一次指令。";
    return;
  }
  if (kind.endsWith("conflict") && (!changedText.trim() || changedText.length > 2000)) {
    turn.error = "请输入与原内容不同的1–2000字内容。";
    return;
  }
  let body: import("./types").ChatPayload | InstructionPayload;
  if (kind === "stale") {
    body = preparedStale ?? {
      runId: turn.runId!, instructionId: id(),
      expectedRevision: turn.result.revision - 1,
      action: "MESSAGE", message: "补充：仍按原出发城市查询。",
    };
    if (body.runId !== turn.runId || body.expectedRevision === undefined
      || body.expectedRevision >= turn.result.revision || body.expectedRevision < 1) {
      turn.error = "任务版本已变化，请重新核对验收请求。";
      return;
    }
  } else if (instruction) {
    body = { ...turn.lastInstruction! };
    if (kind === "instruction-conflict") {
      body.message = changedText.trim();
    }
  } else {
    body = { ...turn.payload };
    if (kind === "request-conflict") {
      body.question = changedText.trim();
    }
  }
  stopIO(turn);
  const generation = turn.generation;
  turn.busy = true;
  turn.error = "";
  let outcome = "";
  let target: string | undefined;
  try {
    const response = await call<Run | Instruction>(turn,
      instruction ? "/api/supervisor/instructions" : "/api/supervisor/chat",
      { method: "POST", body, async: !instruction });
    if (generation !== turn.generation) {
      return;
    }
    target = response.runId;
    outcome = `服务返回任务 ${target}，状态 ${response.state}；请核对是否与原请求一致。`;
  } catch (error) {
    if (generation !== turn.generation) {
      return;
    }
    outcome = error instanceof ApiError ? error.message : "操作结果待确认，请查询原任务。";
    if (error instanceof ApiError && [401, 403, 404].includes(error.status ?? 0)) {
      failure(turn, error);
    }
  } finally {
    if (generation === turn.generation) {
      const trace = turn.traces.at(-1);
      turn.probes.push({
        kind, time: new Date().toLocaleTimeString("zh-CN", { hour12: false }),
        httpStatus: trace?.status ?? null, request: body, outcome, runId: target,
      });
      turn.probes = turn.probes.slice(-30);
      turn.busy = false;
      // 仅恢复查询，不因重放错误丢失此前的已授权结果。
      if (turn.result) {
        await query(turn);
      }
    }
  }
}

/** 读取运行详情；认证仍只来自当前会话令牌，不进入页面存储或导出。 */
export async function loadRunDetail(runId: string): Promise<RunDetail> {
  if (!token) {
    throw new ApiError(null, "请先配置认证令牌。");
  }
  const controller = new AbortController();
  auxiliaryControllers.add(controller);
  try {
    return await request<RunDetail>(
      runDetailPath(runId),
      token,
      { signal: controller.signal, timeout: 10000 },
      trace => {
        diagnosticTraces.value = [...diagnosticTraces.value.slice(-49), trace];
      },
    );
  } finally {
    auxiliaryControllers.delete(controller);
  }
}

/** 按任务编号读取已返回的 RunRes，用于补录历史任务验收快照。 */
export async function loadRunSnapshot(runId: string): Promise<Run> {
  if (!token) {
    throw new ApiError(null, "请先配置认证令牌。");
  }
  const controller = new AbortController();
  auxiliaryControllers.add(controller);
  try {
    return await request<Run>(
      runPath(runId),
      token,
      { signal: controller.signal, timeout: 10000 },
      trace => {
        diagnosticTraces.value = [...diagnosticTraces.value.slice(-49), trace];
      },
    );
  } finally {
    auxiliaryControllers.delete(controller);
  }
}

/** 读取当前实例的脱敏配置证据。 */
export async function loadConfigEvidence(): Promise<ConfigEvidence> {
  if (!token) {
    throw new ApiError(null, "请先配置认证令牌。");
  }
  const controller = new AbortController();
  auxiliaryControllers.add(controller);
  try {
    return await request<ConfigEvidence>(
      configPath(),
      token,
      { signal: controller.signal, timeout: 10000 },
      trace => {
        diagnosticTraces.value = [...diagnosticTraces.value.slice(-49), trace];
      },
    );
  } finally {
    auxiliaryControllers.delete(controller);
  }
}

load();
if (!sessions.length) {
  newSession();
}
