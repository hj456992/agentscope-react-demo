package dev.garden.demo.supervisor.controller;

import dev.garden.demo.supervisor.pojo.res.ErrorRes;

import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/** 对外只返回可理解的错误，不回显内部异常、堆栈和模型凭据。 */
@RestControllerAdvice
public class SupervisorErrorHandler {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorRes> handle(Exception error) {
        if (hasLargeBodyCause(error)) {
            return response(413, "REQUEST_TOO_LARGE", "请求内容过大，请缩短问题或历史记录。");
        }
        if (error instanceof org.springframework.dao.DataAccessException) {
            return response(503, "STORAGE_UNAVAILABLE", "任务存储暂不可用，请稍后重试。");
        }
        if (error instanceof ResponseStatusException statusError) {
            int status = statusError.getStatusCode().value();
            return switch (status) {
                case 400 -> response(400, "INVALID_REQUEST", "请检查 JSON 格式、会话编号、问题长度和历史字段。");
                case 401 -> response(401, "UNAUTHORIZED", "请提供有效身份令牌。");
                case 403 -> response(403, "FORBIDDEN", "当前身份没有该操作权限。");
                case 404 -> response(404, "NOT_FOUND", "记录不存在。");
                case 409 ->
                        response(
                                409,
                                "CONFLICT",
                                statusError.getReason() == null
                                        ? "请求内容、任务版本或状态发生冲突。"
                                        : statusError.getReason());
                case 413 -> response(413, "REQUEST_TOO_LARGE", "待处理指令合计超过 2000 字符，请等待当前补充处理后再提交。");
                case 429 -> response(429, "BUSY", "当前处理任务较多，请稍后重试。");
                case 415 -> response(415, "UNSUPPORTED_MEDIA_TYPE", "请使用 application/json 提交请求。");
                default -> response(status, "REQUEST_ERROR", "请求无法处理，请核对请求路径和方法。");
            };
        }
        return response(500, "INTERNAL_ERROR", "服务暂时无法处理，请稍后重试。");
    }

    private boolean hasLargeBodyCause(Throwable error) {
        // WebFlux 可能将读取上限异常包装为输入异常，因此检查有限层数的原因链。
        for (int depth = 0; error != null && depth < 10; depth++, error = error.getCause()) {
            if (error instanceof DataBufferLimitException) {
                return true;
            }
        }
        return false;
    }

    private ResponseEntity<ErrorRes> response(int status, String code, String message) {
        return ResponseEntity.status(status).body(new ErrorRes(code, message));
    }
}
