package dev.garden.demo.supervisor.pojo.param;

import dev.garden.demo.supervisor.pojo.dto.RequestDTO;
import dev.garden.demo.supervisor.pojo.dto.TurnDTO;

import java.util.List;

/** POST /api/supervisor/chat 的唯一入参。 */
public record ChatParam(
        String sessionId, String question, List<TurnDTO> history, String requestId) {
    public RequestDTO toRequest() {
        return new RequestDTO(sessionId, question, history == null ? List.of() : history, requestId);
    }
}
