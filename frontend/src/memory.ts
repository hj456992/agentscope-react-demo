import { computed, nextTick, onBeforeUnmount, reactive, ref, watch } from "vue";
import { ApiError } from "./api";
import { apiCall, authenticated, credentialEpoch } from "./store";

export const memoryKeys = ["preferredDepartureCity", "preferredSeat", "replyLanguage"] as const;
export type MemoryKey = (typeof memoryKeys)[number];
export interface MemoryItem {
  key: MemoryKey;
  value: string;
  version: number;
  source: string;
  expiresAt: string;
}
export interface MemoryPageRes {
  items: MemoryItem[];
  nextCursor: string | null;
}
export const memoryLabels: Record<MemoryKey, string> = {
  preferredDepartureCity: "出发城市",
  preferredSeat: "席别",
  replyLanguage: "回复语言",
};
export const departureCities = [
  "北京", "上海", "杭州", "南京", "苏州", "宁波", "广州", "深圳", "天津", "重庆", "成都", "武汉", "西安",
  "长沙", "郑州", "济南", "青岛", "合肥", "福州", "厦门", "南昌", "昆明", "贵阳", "南宁", "海口", "沈阳",
  "大连", "长春", "哈尔滨", "石家庄", "太原", "兰州", "西宁", "银川", "呼和浩特", "乌鲁木齐", "拉萨", "香港", "澳门", "台北",
];
export const seatOptions = ["二等座", "一等座", "商务座", "硬座", "软座", "硬卧", "软卧", "无座"];
export const languageOptions = ["中文", "英文", "中英双语"];

/** 每个偏好键只接受服务端白名单中的结构化值，前端不提供自由文本。 */
export function memoryOptions(key: MemoryKey): readonly string[] {
  if (key === "preferredDepartureCity") {
    return departureCities;
  }
  if (key === "preferredSeat") {
    return seatOptions;
  }
  return languageOptions;
}

interface MemoryDraft {
  value: string;
  expiresLocal: string;
  expectedVersion: number;
  dirty: boolean;
  conflict: boolean;
  error: string;
}
function emptyDraft(): MemoryDraft {
  return { value: "", expiresLocal: "", expectedVersion: 0, dirty: false, conflict: false, error: "" };
}
function isMemory(value: unknown): value is MemoryItem {
  if (!value || typeof value !== "object") {
    return false;
  }
  const item = value as Partial<MemoryItem>;
  return memoryKeys.includes(item.key as MemoryKey) && typeof item.value === "string"
    && Number.isInteger(item.version) && Number(item.version) > 0
    && typeof item.source === "string" && typeof item.expiresAt === "string"
    && Number.isFinite(Date.parse(item.expiresAt));
}
export function formatMemoryTime(value: string) {
  return new Date(value).toLocaleString("zh-CN", { hour12: false });
}
function localTime(value: string) {
  const date = new Date(value);
  const pad = (part: number) => String(part).padStart(2, "0");
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}
function readableError(error: unknown, writing = false) {
  if (error instanceof ApiError) {
    if (error.status === 400) {
      return writing ? "偏好未通过校验，请检查城市、席别、回复语言和未来有效期。输入已保留。" : "读取请求未通过校验，分页游标可能已失效，请重新读取第一页。";
    }
    if (error.status === 401) {
      return "认证已失效，请重新配置访问令牌后读取偏好。";
    }
    if (error.status === 403) {
      return writing ? "当前身份没有修改偏好的权限，需要 memory:write 权限。输入已保留。" : "当前身份没有读取偏好的权限。";
    }
    if (error.status === 409) {
      return "偏好版本已变化。输入已保留，请先重新读取最新版本，核对后再保存或删除。";
    }
    if (writing && (error.status === null || (error.status !== null && error.status >= 500))) {
      return "尚未确认操作结果，输入已保留。请先重新读取偏好，确认是否已生效，再决定是否重试。";
    }
    return error.message;
  }
  return "偏好服务暂时不可用，请稍后重新读取。";
}

/** 每个抽屉独立持有内存状态；认证变化和卸载都会使在途响应失效。 */
export function useMemoryPanel() {
  const items = ref<MemoryItem[]>([]);
  const nextCursor = ref<string | null>(null);
  const loading = ref(false);
  const loaded = ref(false);
  const busyKey = ref<MemoryKey | null>(null);
  const error = ref("");
  const notice = ref("");
  const now = ref(Date.now());
  const drafts = reactive<Record<MemoryKey, MemoryDraft>>({ preferredDepartureCity: emptyDraft(), preferredSeat: emptyDraft(), replyLanguage: emptyDraft() });
  let generation = 0;
  let disposed = false;
  const clock = window.setInterval(() => { now.value = Date.now(); }, 1000);
  const activeItems = computed(() => items.value.filter(item => Date.parse(item.expiresAt) > now.value));
  const pending = computed(() => loading.value || busyKey.value !== null);
  const currentItem = (key: MemoryKey) => activeItems.value.find(item => item.key === key);
  const known = (key: MemoryKey) => loaded.value && (!nextCursor.value || Boolean(currentItem(key)));
  const hasExpired = (key: MemoryKey) => Boolean(items.value.find(item => item.key === key && Date.parse(item.expiresAt) <= now.value));
  const valid = (request: number, epoch: number) => !disposed && request === generation && epoch === credentialEpoch.value;

  function touch(key: MemoryKey) {
    drafts[key].dirty = true;
    drafts[key].error = "";
    notice.value = "";
  }
  function synchronizeDrafts() {
    for (const key of memoryKeys) {
      if (!known(key)) {
        continue;
      }
      const item = currentItem(key);
      const draft = drafts[key];
      draft.expectedVersion = item?.version ?? 0;
      draft.conflict = false;
      draft.error = "";
      if (!draft.dirty) {
        draft.value = item?.value ?? "";
        draft.expiresLocal = item ? localTime(item.expiresAt) : "";
      }
    }
  }
  async function read(more = false) {
    if (!authenticated.value || pending.value || (more && !nextCursor.value)) {
      return;
    }
    const request = ++generation;
    const epoch = credentialEpoch.value;
    const cursor = more ? nextCursor.value : null;
    loading.value = true;
    error.value = "";
    notice.value = "";
    try {
      const page = await apiCall<MemoryPageRes>(`/api/supervisor/memories${cursor ? `/${encodeURIComponent(cursor)}` : ""}`);
      if (!valid(request, epoch)) {
        return;
      }
      if (!page || !Array.isArray(page.items) || !page.items.every(isMemory)
        || (page.nextCursor !== null && typeof page.nextCursor !== "string") || (cursor && page.nextCursor === cursor)) {
        throw new Error("偏好响应格式不正确");
      }
      now.value = Date.now();
      const merged = new Map((more ? items.value : []).map(item => [item.key, item]));
      for (const item of page.items) {
        merged.set(item.key, item);
      }
      items.value = [...merged.values()];
      nextCursor.value = page.nextCursor || null;
      loaded.value = true;
      synchronizeDrafts();
      notice.value = more ? "已读取下一页偏好。" : "已读取当前身份的最新偏好；未保存的输入会保留。";
    } catch (cause) {
      if (valid(request, epoch) && !clearAccessFailure(cause, true)) {
        error.value = readableError(cause);
      }
    } finally {
      if (valid(request, epoch)) {
        loading.value = false;
      }
    }
  }
  async function save(key: MemoryKey) {
    const draft = drafts[key];
    if (!authenticated.value || pending.value || !known(key) || draft.conflict || hasExpired(key)) {
      return;
    }
    const value = draft.value.trim();
    if (!memoryOptions(key).includes(value)) {
      draft.error = key === "preferredDepartureCity"
        ? "请选择演示支持的城市名称。"
        : key === "preferredSeat" ? "请选择有效的席别。" : "请选择有效的回复语言。";
      return;
    }
    let expiresAt: string | null = null;
    if (draft.expiresLocal) {
      const expiry = new Date(draft.expiresLocal);
      if (!Number.isFinite(expiry.getTime()) || expiry.getTime() <= Date.now()) {
        draft.error = "有效期需晚于当前本地时间；留空则使用服务端默认有效期。";
        return;
      }
      expiresAt = expiry.toISOString();
    }
    const request = ++generation;
    const epoch = credentialEpoch.value;
    busyKey.value = key;
    error.value = "";
    notice.value = "";
    draft.error = "";
    try {
      const item = await apiCall<MemoryItem>(`/api/supervisor/memories/${encodeURIComponent(key)}`, {
        method: "PUT", body: { value, expectedVersion: draft.expectedVersion, expiresAt },
      });
      if (!valid(request, epoch)) {
        return;
      }
      if (!isMemory(item) || item.key !== key) {
        throw new ApiError(null, "偏好响应无法识别，请重新读取确认结果。");
      }
      items.value = [...items.value.filter(entry => entry.key !== key), item];
      now.value = Date.now();
      draft.dirty = false;
      synchronizeDrafts();
      notice.value = `已保存${memoryLabels[key]}“${item.value}”，当前版本 ${item.version}。`;
    } catch (cause) {
      if (valid(request, epoch) && !clearAccessFailure(cause)) {
        draft.error = readableError(cause, true);
        draft.conflict = cause instanceof ApiError && (cause.status === 409 || cause.status === null || (cause.status !== null && cause.status >= 500));
      }
    } finally {
      if (valid(request, epoch)) {
        busyKey.value = null;
      }
    }
  }
  async function remove(item: MemoryItem) {
    if (!authenticated.value || pending.value || drafts[item.key].conflict) {
      return;
    }
    const request = ++generation;
    const epoch = credentialEpoch.value;
    busyKey.value = item.key;
    error.value = "";
    notice.value = "";
    try {
      await apiCall<void>(`/api/supervisor/memories/${encodeURIComponent(item.key)}/${item.version}`, { method: "DELETE" });
      if (!valid(request, epoch)) {
        return;
      }
      items.value = items.value.filter(entry => entry.key !== item.key);
      Object.assign(drafts[item.key], emptyDraft());
      notice.value = `已删除${memoryLabels[item.key]}“${item.value}”。`;
    } catch (cause) {
      if (valid(request, epoch) && !clearAccessFailure(cause)) {
        drafts[item.key].error = readableError(cause, true);
        drafts[item.key].conflict = cause instanceof ApiError && (cause.status === 409 || cause.status === null || (cause.status !== null && cause.status >= 500));
      }
    } finally {
      if (valid(request, epoch)) {
        busyKey.value = null;
      }
    }
  }
  /** 读取被拒绝时撤下旧内容；写入403只表示没有写权限，不推断读取权限被撤销。 */
  function clearAccessFailure(cause: unknown, reading = false) {
    if (!(cause instanceof ApiError) || (cause.status !== 401 && !(reading && cause.status === 403))) {
      return false;
    }
    clear();
    error.value = readableError(cause);
    return true;
  }
  function clear() {
    generation++;
    items.value = [];
    nextCursor.value = null;
    loaded.value = false;
    loading.value = false;
    busyKey.value = null;
    error.value = "";
    notice.value = "";
    for (const key of memoryKeys) {
      Object.assign(drafts[key], emptyDraft());
    }
  }
  watch(credentialEpoch, () => {
    clear();
    const epoch = credentialEpoch.value;
    void nextTick(() => {
      if (!disposed && epoch === credentialEpoch.value && authenticated.value) {
        void read();
      }
    });
  }, { flush: "sync" });
  onBeforeUnmount(() => {
    disposed = true;
    clear();
    clearInterval(clock);
  });
  return { items, activeItems, nextCursor, loading, loaded, busyKey, pending, error, notice, drafts, currentItem, known, hasExpired, touch, read, save, remove };
}
