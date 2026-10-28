package dev.garden.demo.supervisor.pojo.dto;

/** 排空用户补充输入的结果；consumedRevision 需要回写到运行控制对象。 */
public record DrainResultDTO(String text, int consumedRevision) {}
