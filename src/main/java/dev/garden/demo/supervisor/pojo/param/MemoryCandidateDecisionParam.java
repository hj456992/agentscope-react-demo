package dev.garden.demo.supervisor.pojo.param;

/** 审核记忆候选；decision 只接受 APPROVE 或 REJECT。 */
public record MemoryCandidateDecisionParam(String candidateId, String decision, String reason) {}
