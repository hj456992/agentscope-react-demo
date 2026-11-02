package dev.garden.demo.supervisor.pojo.dto;

import java.util.List;

/**
 * HTTP 历史由服务端补充；调用方历史只作为待核实资料。
 *
 * <p>{@code acceptanceHint} 只用于受控验收分支，默认 {@code null}；是否生效由
 * {@code supervisor.acceptance-hints-enabled} 决定，不能借此提升权限或预算。
 */
public record RequestDTO(
        String sessionId,
        String question,
        List<TurnDTO> history,
        String requestId,
        String acceptanceHint) {
    public RequestDTO(String sessionId, String question, List<TurnDTO> history, String requestId) {
        this(sessionId, question, history, requestId, null);
    }

    public RequestDTO(String sessionId, String question, List<TurnDTO> history) {
        this(sessionId, question, history, null, null);
    }

    public RequestDTO {
        history = history == null ? List.of() : List.copyOf(history);
    }
}
