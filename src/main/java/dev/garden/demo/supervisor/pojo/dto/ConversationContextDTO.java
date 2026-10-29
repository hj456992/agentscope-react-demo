package dev.garden.demo.supervisor.pojo.dto;

import java.util.Set;

/** 服务端装配后的请求及实际可见历史内容的来源权限，不进入用户请求协议。 */
public record ConversationContextDTO(RequestDTO request, Set<String> requiredPermissions) {
    public ConversationContextDTO {
        requiredPermissions = Set.copyOf(requiredPermissions);
    }
}
