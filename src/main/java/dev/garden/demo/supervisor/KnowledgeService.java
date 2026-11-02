package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.*;

import dev.garden.demo.supervisor.pojo.dto.*;

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
    private final String location;

    /**
     * 加载本地资料。
     *
     * <p>默认读取 classpath 示例；设置环境变量 {@code SUPERVISOR_KNOWLEDGE_FILE} 时读取
     * 指定本地文件，用于验证“可编辑检索内容被当作数据”的信任边界。文件缺失时明确报错，
     * 不静默回退到默认资料。
     */
    KnowledgeService() {
        String configured = System.getenv("SUPERVISOR_KNOWLEDGE_FILE");
        if (configured != null && !configured.isBlank()) {
            java.nio.file.Path path = java.nio.file.Path.of(configured.strip());
            try {
                document = parse(java.nio.file.Files.readString(path, StandardCharsets.UTF_8));
            } catch (IOException ex) {
                throw new UncheckedIOException("无法读取 SUPERVISOR_KNOWLEDGE_FILE", ex);
            }
            location = path.toAbsolutePath().toString();
            return;
        }
        try (InputStream stream = getClass().getResourceAsStream("/supervisor/travel-guide.md")) {
            if (stream == null) {
                throw new IllegalStateException("缺少示例资料");
            }
            document = parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        location = "classpath:/supervisor/travel-guide.md";
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
            if (field.length == 2) {
                meta.put(field[0].strip(), field[1].strip());
            }
        }
        int start = boundary;
        while (start < lines.length && (lines[start].startsWith("#") || lines[start].isBlank()))
            start++;
        String quote = String.join("\n", Arrays.copyOfRange(lines, start, lines.length)).strip();
        // 资料文件来自外部配置，缺字段时在这里明确报错；
        // 不能等到检索时把空值写进证据并引发空指针，让调用看起来像被取消。
        for (String key : List.of("id", "version", "expires", "keywords")) {
            if (meta.get(key) == null || meta.get(key).isBlank()) {
                throw new IllegalStateException("资料文件缺少必需字段：" + key);
            }
        }
        if (quote.isBlank()) {
            throw new IllegalStateException("资料文件缺少正文内容");
        }
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

    EvidenceDTO search(String query, LocalDate today) {
        if (!document.expires().isAfter(today)
                || document.keywords().stream().noneMatch(query::contains)) {
            return new EvidenceDTO("search_knowledge", Status.NO_DATA, "local-markdown", Map.of());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("documentId", document.id());
        data.put("version", document.version());
        data.put("expiresAt", document.expires().toString());
        data.put("line", document.line());
        data.put("quote", document.quote());
        data.put("trustLevel", "RETRIEVAL");
        return new EvidenceDTO(
                "search_knowledge",
                Status.SUCCESS,
                location,
                data);
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
                            if (!context.control.identity().allows(getName())) {
                                context.correction(
                                        CorrectionType.PERMISSION_DENIED, getName());
                                return Mono.just(ToolResultBlock.error("PERMISSION_DENIED"));
                            }
                            if (ToolValidator.validateInput(json(param.getInput()), getParameters())
                                    != null) {
                                context.correction(CorrectionType.INVALID_SCHEMA, getName());
                                return Mono.just(ToolResultBlock.error("INVALID_SCHEMA"));
                            }
                            // 资料查询约束来自父任务，不采用模型任意扩大后的查询文本。
                            EvidenceDTO found =
                                    search(
                                            context.query,
                                            LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")));
                            context.add(found);
                            context.correctionSuccess(getName());
                            return Mono.just(ToolResultBlock.text(json(found)));
                        });
            }
        };
    }
}
