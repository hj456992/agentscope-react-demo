package dev.garden.demo.supervisor.pojo.res;

/** 运行中指令的处理结果。 */
public record InstructionRes(String runId, int revision, String state, String message) {}
