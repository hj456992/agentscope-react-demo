package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.*;

import reactor.core.publisher.Mono;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/** 带来源与有效期的本地资料检索，示范检索证据如何进入回答校验。 */
final class KnowledgeService {
    record Document(
            String id,
            String version,
            LocalDate expires,
            List<String> keywords,
            String quote,
            int line) {}

    private final Document document;

    KnowledgeService() {
        try (InputStream stream = getClass().getResourceAsStream("/supervisor/travel-guide.md")) {
            if (stream == null) throw new IllegalStateException("缺少示例资料");
            document = parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static Document parse(String text) {
        String[] lines = text.split("\\R");
        Map<String, String> meta = new HashMap<>();
        int boundary = 0;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].equals("---")) {
                boundary = i + 1;
                break;
            }
            String[] field = lines[i].split(":", 2);
            if (field.length == 2) meta.put(field[0].strip(), field[1].strip());
        }
        int start = boundary;
        while (start < lines.length && (lines[start].startsWith("#") || lines[start].isBlank()))
            start++;
        String quote = String.join("\n", Arrays.copyOfRange(lines, start, lines.length)).strip();
        return new Document(
                meta.get("id"),
                meta.get("version"),
                LocalDate.parse(meta.get("expires")),
                List.of(meta.get("keywords").split(",")),
                quote,
                start + 1);
    }

    String version() {
        return RunContext.fingerprint(document);
    }

    Evidence search(String query, LocalDate today) {
        if (!document.expires().isAfter(today)
                || document.keywords().stream().noneMatch(query::contains))
            return new Evidence("search_knowledge", Status.NO_DATA, "local-markdown", Map.of());
        return new Evidence(
                "search_knowledge",
                Status.SUCCESS,
                "classpath:/supervisor/travel-guide.md",
                Map.of(
                        "documentId",
                        document.id(),
                        "version",
                        document.version(),
                        "expiresAt",
                        document.expires().toString(),
                        "line",
                        document.line(),
                        "quote",
                        document.quote()));
    }

    AgentTool tool(RunContext context) {
        return new AgentTool() {
            public String getName() {
                return "search_knowledge";
            }

            public String getDescription() {
                return "查询行程安排示例资料，返回原文、位置、版本和有效期。";
            }

            public Map<String, Object> getParameters() {
                return Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("query", Map.of("type", "string", "maxLength", 2000)),
                        "required",
                        List.of("query"),
                        "additionalProperties",
                        false);
            }

            public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
                return Mono.defer(
                        () -> {
                            context.check();
                            if (!context.control.identity().allows(getName()))
                                return Mono.just(ToolResultBlock.error("PERMISSION_DENIED"));
                            if (ToolValidator.validateInput(json(param.getInput()), getParameters())
                                    != null) {
                                context.correction("knowledge", "INVALID_SCHEMA");
                                return Mono.just(ToolResultBlock.error("INVALID_SCHEMA"));
                            }
                            // 资料查询约束来自父任务，不采用模型任意扩大后的查询文本。
                            Evidence found =
                                    search(
                                            context.query,
                                            LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")));
                            context.add(found);
                            return Mono.just(ToolResultBlock.text(json(found)));
                        });
            }
        };
    }
}
