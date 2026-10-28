package dev.garden.demo.supervisor.pojo.res;

/** 对外错误响应，不回显内部异常、堆栈和模型凭据。 */
public record ErrorRes(String code, String message) {}
