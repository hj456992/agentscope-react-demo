package dev.garden.demo.supervisor.pojo.req;

/** PUT /prompts/{promptId}/{version} 的请求体：只允许更新元数据和内容正文。 */
public record PromptUpdateReq(
        String owner, String content, String evalRecord, String status, String actor) {}
