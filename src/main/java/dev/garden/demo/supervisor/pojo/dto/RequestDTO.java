package dev.garden.demo.supervisor.pojo.dto;

import java.util.List;

/** HTTP 历史由服务端补充；调用方历史只作为待核实资料。 */
public record RequestDTO(
        String sessionId, String question, List<TurnDTO> history, String requestId) {
    public RequestDTO(String sessionId, String question, List<TurnDTO> history) {
        this(sessionId, question, history, null);
    }

    public RequestDTO {
        history = history == null ? List.of() : List.copyOf(history);
    }
}
