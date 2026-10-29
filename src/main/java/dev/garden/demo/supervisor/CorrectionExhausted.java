package dev.garden.demo.supervisor;

import dev.garden.demo.supervisor.pojo.dto.Status;

/**
 * 纠错预算耗尽时终止本轮运行的内部异常。
 *
 * <p>只由 {@code Supervisor.run} 转成 {@link dev.garden.demo.supervisor.pojo.dto.ResultDTO}，
 * 不进入公开 HTTP 错误码；工具层必须原样重新抛出，不能吞成普通失败证据。
 */
final class CorrectionExhausted extends RuntimeException {
    private final Status status;

    CorrectionExhausted(Status status, String message) {
        super(message);
        this.status = status;
    }

    Status status() {
        return status;
    }
}
