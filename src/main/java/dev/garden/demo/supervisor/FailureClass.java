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
}
