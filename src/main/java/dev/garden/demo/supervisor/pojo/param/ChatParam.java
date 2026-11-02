package dev.garden.demo.supervisor.pojo.param;

import dev.garden.demo.supervisor.pojo.dto.RequestDTO;
import dev.garden.demo.supervisor.pojo.dto.TurnDTO;

import java.util.List;

/**
 * POST /api/supervisor/chat 的唯一入参。
 *
 * <p>{@code acceptanceHint} 为空时行为完全不变；只有实例显式开启受控验收开关后，
 * 白名单内的分支提示才会生效，并在运行详情中标注 {@code triggerMode=CONTROLLED}。
 */
public record ChatParam(
        String sessionId,
        String question,
        List<TurnDTO> history,
        String requestId,
        String acceptanceHint) {
    public RequestDTO toRequest() {
        return new RequestDTO(
                sessionId,
                question,
                history == null ? List.of() : history,
                requestId,
                acceptanceHint);
    }
}
