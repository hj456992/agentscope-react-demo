package dev.garden.demo.supervisor;

/** 模型与工具共用的失败分类；只有前三个类别允许自动重试。 */
public enum FailureClass {
    RETRYABLE(true),
    RATE_LIMITED(true),
    TIMEOUT(true),
    QUOTA_EXHAUSTED(false),
    AUTH(false),
    INPUT_INVALID(false),
    NOT_FOUND(false),
    CANCELLED(false),
    BUDGET(false),
    CONTEXT_LIMIT(false),
    UNKNOWN(false);

    private final boolean retryable;

    FailureClass(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }

    /** 按 HTTP 状态映射失败类别；供应商与本地工具故障共用同一套判定。 */
    public static FailureClass fromStatus(int status) {
        if (status == 408) {
            return TIMEOUT;
        }
        if (status == 429) {
            return RATE_LIMITED;
        }
        if (status == 401 || status == 403) {
            return AUTH;
        }
        if (status == 400 || status == 422) {
            return INPUT_INVALID;
        }
        if (status == 404) {
            return NOT_FOUND;
        }
        if (status >= 500 && status < 600) {
            return RETRYABLE;
        }
        return UNKNOWN;
    }
}
