package dev.garden.demo.supervisor.pojo.dto;

/** 请求前置过滤器解析出的公共请求信息，经 Reactor context 传给 Controller。 */
public record RequestContextDTO(IdentityDTO identity, boolean async) {}
