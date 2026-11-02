package dev.garden.demo.supervisor.service;

import dev.garden.demo.supervisor.pojo.dataobject.PromptDO;
import dev.garden.demo.supervisor.pojo.dataobject.PromptReleaseDO;
import dev.garden.demo.supervisor.pojo.dto.PromptDefinitionDTO;
import dev.garden.demo.supervisor.pojo.param.PromptParam;
import dev.garden.demo.supervisor.pojo.param.PromptReleaseParam;
import dev.garden.demo.supervisor.pojo.req.PromptUpdateReq;
import dev.garden.demo.supervisor.pojo.res.PromptReleaseRes;
import dev.garden.demo.supervisor.pojo.res.PromptRes;
import dev.garden.demo.supervisor.repo.GovernanceRepository;
import dev.garden.demo.supervisor.support.PromptCatalog;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 提示词注册表：按角色和版本提供实际注入模型的正文，并把角色固定到一个版本。
 *
 * <p>首次启动把进程内静态提示词登记为版本 1，同时写入角色发布指针。运行中的 Agent
 * 通过 {@code RunControl} 读取当前发布版本；任务把实际版本和哈希写入检查点，之后即使
 * 注册表发布新版本，在途任务也不漂移。HTTP 读取接口只返回元数据，不返回正文。
 */
@Service
public final class PromptRegistryService {
    private static final Set<String> STATUSES = Set.of("DRAFT", "PUBLISHED", "RETIRED");
    private static final int MAX_CONTENT = 20_000;
    private static final int MAX_EVAL = 4_000;

    private final GovernanceRepository repository;
    private final Clock clock;

    /** 唯一构造器：建表由仓储完成，这里只登记内置版本。 */
    public PromptRegistryService(GovernanceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
        seed();
    }

    /** 返回角色当前发布版本；注册表缺少记录时退回静态目录，保证任务仍可启动。 */
    public PromptDefinitionDTO active(String role) {
        String normalized = normalizeRole(role);
        PromptDO row = repository.activePrompt(normalized);
        if (row == null) {
            return PromptCatalog.definition(normalized);
        }
        return new PromptDefinitionDTO(
                row.role,
                row.promptId,
                row.version,
                row.contentHash,
                row.content,
                "registry",
                row.owner,
                row.status,
                row.evalRecord == null ? "" : row.evalRecord);
    }

    public List<PromptRes> list() {
        List<PromptRes> result = new ArrayList<>();
        for (PromptDO row : repository.prompts()) {
            result.add(view(row));
        }
        return result;
    }

    public PromptRes get(String promptId, String version) {
        PromptDO row = repository.prompt(promptId, version);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return view(row);
    }

    public PromptRes create(PromptParam param) {
        if (param == null
                || blank(param.promptId())
                || blank(param.version())
                || blank(param.role())
                || blank(param.content())
                || param.promptId().length() > 200
                || param.version().length() > 80
                || param.content().length() > MAX_CONTENT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (repository.prompt(param.promptId(), param.version()) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        return save(
                param.promptId(),
                param.version(),
                param.role(),
                blank(param.owner()) ? "travel-platform" : param.owner(),
                param.content(),
                param.evalRecord(),
                status(param.status()),
                blank(param.actor()) ? "platform-admin" : param.actor(),
                null);
    }

    public PromptRes update(String promptId, String version, PromptUpdateReq req) {
        PromptDO current = repository.prompt(promptId, version);
        if (current == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String content = req.content() == null ? current.content : req.content();
        if (content.isBlank() || content.length() > MAX_CONTENT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String status = req.status() == null ? current.status : status(req.status());
        String eval = req.evalRecord() == null ? current.evalRecord : req.evalRecord();
        return save(
                promptId,
                version,
                current.role,
                blank(req.owner()) ? current.owner : req.owner(),
                content,
                eval,
                status,
                blank(req.actor()) ? current.createdBy : req.actor(),
                current);
    }

    public void release(PromptReleaseParam param) {
        if (param == null || blank(param.role()) || blank(param.promptId()) || blank(param.version())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        String role = normalizeRole(param.role());
        PromptDO prompt = repository.prompt(param.promptId(), param.version());
        if (prompt == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        if (!prompt.role.equals(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (!"PUBLISHED".equals(prompt.status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        PromptReleaseDO row = new PromptReleaseDO();
        row.role = role;
        row.promptId = prompt.promptId;
        row.version = prompt.version;
        row.actor = blank(param.actor()) ? "platform-admin" : param.actor();
        row.updatedAt = clock.millis();
        repository.releasePrompt(row);
    }

    public List<PromptReleaseRes> releases() {
        List<PromptReleaseRes> result = new ArrayList<>();
        for (PromptReleaseDO row : repository.promptReleases()) {
            result.add(
                    new PromptReleaseRes(
                            row.role, row.promptId, row.version, row.actor, row.updatedAt));
        }
        return result;
    }

    private void seed() {
        if (repository.prompts().isEmpty()) {
            for (String role :
                    List.of("supervisor", "weather_agent", "train_agent", "itinerary_agent")) {
                PromptDefinitionDTO definition = PromptCatalog.definition(role);
                PromptDO row = new PromptDO();
                row.promptId = definition.promptId();
                row.version = "1";
                row.role = definition.role();
                row.owner = "travel-platform";
                row.status = "PUBLISHED";
                row.content = definition.content();
                row.evalRecord = "static-catalog-baseline";
                row.contentHash = PromptCatalog.sha256(row.content);
                row.createdBy = "system-seed";
                row.createdAt = clock.millis();
                repository.savePrompt(row);
            }
        }
        if (repository.promptReleases().isEmpty()) {
            for (PromptDO row : repository.prompts()) {
                if (!"1".equals(row.version) || !"PUBLISHED".equals(row.status)) {
                    continue;
                }
                PromptReleaseDO release = new PromptReleaseDO();
                release.role = row.role;
                release.promptId = row.promptId;
                release.version = row.version;
                release.actor = "system-seed";
                release.updatedAt = clock.millis();
                repository.releasePrompt(release);
            }
        }
    }

    private PromptRes save(
            String promptId,
            String version,
            String role,
            String owner,
            String content,
            String evalRecord,
            String status,
            String actor,
            PromptDO current) {
        if ("PUBLISHED".equals(status) && blank(evalRecord)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        if (evalRecord != null && evalRecord.length() > MAX_EVAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        PromptDO row = new PromptDO();
        row.promptId = promptId;
        row.version = version;
        row.role = normalizeRole(role);
        row.owner = owner;
        row.status = status;
        row.content = content;
        row.evalRecord = evalRecord == null ? "" : evalRecord;
        row.contentHash = PromptCatalog.sha256(content);
        row.createdBy = actor;
        row.createdAt = current == null ? clock.millis() : current.createdAt;
        repository.savePrompt(row);
        return view(row);
    }

    private static PromptRes view(PromptDO row) {
        return new PromptRes(
                row.promptId,
                row.version,
                row.role,
                row.owner,
                row.status,
                row.contentHash,
                row.evalRecord,
                row.createdBy,
                row.createdAt);
    }

    private static String status(String value) {
        String normalized = blank(value) ? "DRAFT" : value.strip().toUpperCase(java.util.Locale.ROOT);
        if (!STATUSES.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private static String normalizeRole(String role) {
        return switch (role == null ? "" : role.strip()) {
            case "weather_agent", "train_agent", "itinerary_agent" -> role.strip();
            default -> "supervisor";
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
