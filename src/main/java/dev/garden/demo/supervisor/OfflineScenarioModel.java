package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.support.Contracts.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/**
 * 离线协议脚本，仅模拟文档中的天气、车票、行程场景，不具备通用自然语言理解能力。
 * 脚本替代模型服务，父子循环、协作工具、真实工具结果与压缩流程仍由正式运行时执行。
 * 所有词法匹配只存在于此离线模型，不能作为生产工具的业务理解或权限依据。
 */
public final class OfflineScenarioModel implements Model {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String CITY_PATTERN = "杭州|上海|北京|成都";
    private static final Pattern CITY = Pattern.compile(CITY_PATTERN);
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private final String role;

    public OfflineScenarioModel(String role) {
        this.role = role;
    }

    @Override
    public String getModelName() {
        return "offline-scenario-" + role;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> {
            try {
                if (role.equals("compact") || role.equals("summary")) {
                    return Flux.just(summarize(messages));
                }
                int current = latestUser(messages);
                if (current < 0) {
                    return Flux.just(answer("离线演示尚未收到可处理的用户问题。"));
                }
                List<Msg> turn = messages.subList(current, messages.size());
                Scenario task = resolve(messages, current, referenceDate(messages));
                ChatResponse response = switch (role) {
                    case "supervisor" -> supervise(task, turn, tools);
                    case "weather_agent", "train_agent" -> query(task, turn, tools);
                    case "itinerary_agent" -> itinerary(task, turn, tools);
                    default -> throw new IllegalArgumentException("未知离线角色：" + role);
                };
                return Flux.just(response);
            } catch (Exception error) {
                return Flux.error(error);
            }
        });
    }

    private ChatResponse supervise(Scenario task, List<Msg> turn, List<ToolSchema> tools) {
        if (task.intent().equals("UNKNOWN")) {
            return answer("我可以查询天气、火车票和规划行程，使用本地示例数据；离线脚本仅覆盖固定演示场景。");
        }
        String missing = missing(task);
        if (!missing.isEmpty()) {
            return clarify(missing, tools);
        }
        String childRole = switch (task.intent()) {
            case "WEATHER" -> "weather_agent";
            case "TRAIN" -> "train_agent";
            default -> "itinerary_agent";
        };
        if (!spawned(turn, childRole)) {
            return spawn(childRole, task, tools);
        }
        if (terminalChildren(turn) == 0) {
            if (hasToolFailure(turn, "spawn_agent")) {
                return answer("协作工具未能启动所需助手，本次没有得到查询结果。\n" + toolText(turn, "spawn_agent"));
            }
            return tool("wait_agent", Map.of("timeout_ms", 10000), tools);
        }
        return answer(render(turn) + (task.intent().equals("ITINERARY") ? itineraryAdvice(task.days()) : ""));
    }

    private ChatResponse query(Scenario task, List<Msg> turn, List<ToolSchema> tools) {
        String queryTool = role.equals("weather_agent") ? "query_weather" : "query_trains";
        if (hasResult(turn, queryTool)) {
            return answer(render(turn));
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("date", task.date());
        if (role.equals("weather_agent")) {
            parameters.put("city", task.city().isEmpty() ? task.to() : task.city());
        } else {
            parameters.put("from", task.from());
            parameters.put("to", task.to());
        }
        return tool(queryTool, parameters, tools);
    }

    private ChatResponse itinerary(Scenario task, List<Msg> turn, List<ToolSchema> tools) {
        if (!spawned(turn, "weather_agent")) {
            return spawn("weather_agent", new Scenario("WEATHER", task.to(), "", "", task.date(), 0, task.query()), tools);
        }
        if (!spawned(turn, "train_agent")) {
            return spawn("train_agent", new Scenario("TRAIN", "", task.from(), task.to(), task.date(), 0, task.query()), tools);
        }
        if (available(tools, "search_knowledge") && !hasResult(turn, "search_knowledge")) {
            return tool("search_knowledge", Map.of("query", task.query()), tools);
        }
        if (terminalChildren(turn) < 2) {
            if (hasToolFailure(turn, "spawn_agent")) {
                return answer(render(turn) + "\n部分助手未能启动，行程信息尚不完整。");
            }
            return tool("wait_agent", Map.of("timeout_ms", 10000), tools);
        }
        return answer(render(turn) + itineraryAdvice(task.days()));
    }

    private ChatResponse spawn(String childRole, Scenario task, List<ToolSchema> tools) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("intent", task.intent());
        message.put("city", task.city());
        message.put("from", task.from());
        message.put("to", task.to());
        message.put("date", task.date());
        message.put("days", task.days());
        message.put("query", task.query());
        String taskName = childRole.replace("_agent", "") + "_" + UUID.randomUUID().toString().substring(0, 8);
        return tool("spawn_agent", Map.of("task_name", taskName, "message", json(message),
                "fork_turns", "none", "agent_type", childRole), tools);
    }

    private ChatResponse clarify(String question, List<ToolSchema> tools) {
        if (!available(tools, "request_user_input")) {
            return answer(question);
        }
        return tool("request_user_input", Map.of("questions", List.of(Map.of("id", "conditions", "header", "查询条件", "question", question))), tools);
    }

    /** 读取真实用户历史与真实问答工具结果；摘要、子助手通知都不是新的用户要求。 */
    private Scenario resolve(List<Msg> messages, int current, LocalDate today) {
        String original = messages.get(current).getTextContent();
        String supplied = messages.subList(current + 1, messages.size()).stream()
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .filter(result -> "request_user_input".equals(result.getName()))
                .map(OfflineScenarioModel::resultText)
                .flatMap(text -> objects(text).stream())
                .flatMap(node -> answerValues(node.path("answers")).stream())
                .collect(Collectors.joining(" "));
        Scenario currentTask = parse(original + (supplied.isEmpty() ? "" : " " + supplied), today);
        boolean continuation = original.matches("(?s)^(那|改|后天|明天|今天).*")
                || !currentTask.city().isEmpty() || !currentTask.date().isEmpty() || currentTask.days() > 0;
        if (currentTask.intent().equals("UNKNOWN") && !continuation
                || !currentTask.intent().equals("UNKNOWN") && missing(currentTask).isEmpty()) {
            return currentTask;
        }
        for (int i = current - 1; i >= 0; i--) {
            Msg prior = messages.get(i);
            if (realUser(prior)) {
                Scenario earlier = parse(prior.getTextContent(), today);
                currentTask = currentTask.fillMissing(earlier);
                if (!currentTask.intent().equals("UNKNOWN")) {
                    break;
                }
            }
        }
        return currentTask;
    }

    private Scenario parse(String text, LocalDate today) {
        List<JsonNode> jsonObjects = objects(text);
        if (text.stripLeading().startsWith("{") && !jsonObjects.isEmpty()) {
            JsonNode node = jsonObjects.get(0);
            if (node.has("decision")) {
                node = node.path("decision");
            }
            if (node.has("city") || node.has("from") || node.has("date")) {
                String intent = node.path("intent").asText(role.equals("weather_agent") ? "WEATHER" : role.equals("train_agent") ? "TRAIN" : "ITINERARY");
                return new Scenario(intent, node.path("city").asText(), node.path("from").asText(), node.path("to").asText(),
                        node.path("date").asText(), node.path("days").asInt(), node.path("query").asText(text));
            }
        }
        String query = text.replace("后天", today.plusDays(2).toString()).replace("明天", today.plusDays(1).toString()).replace("今天", today.toString());
        boolean weather = query.contains("天气");
        boolean train = query.contains("火车") || query.contains("高铁") || query.contains("车票");
        String intent = query.contains("行程") || query.contains("玩") || weather && train ? "ITINERARY" : weather ? "WEATHER" : train ? "TRAIN" : "UNKNOWN";
        String city = match(CITY, query, 0);
        String from = match(Pattern.compile("从(" + CITY_PATTERN + ")"), query, 1);
        String to = match(Pattern.compile("(?:到|去)(" + CITY_PATTERN + ")"), query, 1);
        Matcher route = Pattern.compile("(" + CITY_PATTERN + ")(?:到|去)(" + CITY_PATTERN + ")").matcher(query);
        if (route.find()) {
            from = route.group(1);
            to = route.group(2);
        }
        String dayCount = match(Pattern.compile("(\\d+)天"), query, 1);
        int days = dayCount.isEmpty() ? query.contains("一天") ? 1 : query.contains("两天") || query.contains("二天") ? 2 : query.contains("三天") ? 3 : 0 : Integer.parseInt(dayCount);
        return new Scenario(intent, city, from, to, match(DATE, query, 0), days, text);
    }

    private record Scenario(String intent, String city, String from, String to, String date, int days, String query) {
        Scenario fillMissing(Scenario previous) {
            return new Scenario(intent.equals("UNKNOWN") ? previous.intent : intent, fallback(city, previous.city), fallback(from, previous.from),
                    fallback(to, previous.to), fallback(date, previous.date), days == 0 ? previous.days : days, previous.query + "\n" + query);
        }
    }

    private static String missing(Scenario task) {
        if (task.intent().equals("WEATHER") && task.city().isEmpty()) {
            return "请告诉我要查询天气的城市。";
        }
        if (!task.intent().equals("WEATHER") && task.from().isEmpty()) {
            return "请告诉我出发城市。";
        }
        if (!task.intent().equals("WEATHER") && task.to().isEmpty()) {
            return "请告诉我目的地城市。";
        }
        if (task.date().isEmpty()) {
            return "请告诉我查询日期。";
        }
        if (task.intent().equals("ITINERARY") && task.days() == 0) {
            return "请告诉我行程天数。";
        }
        return "";
    }

    private static int latestUser(List<Msg> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (realUser(messages.get(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean realUser(Msg message) {
        return message.getRole() == MsgRole.USER && !ContextCompactor.isSummary(message)
                && !message.getTextContent().stripLeading().startsWith("<agent_message")
                && !message.getTextContent().contains("CONTEXT CHECKPOINT COMPACTION");
    }

    private static LocalDate referenceDate(List<Msg> messages) {
        for (Msg message : messages) {
            if (message.getRole() == MsgRole.SYSTEM) {
                for (JsonNode node : objects(message.getTextContent())) {
                    if (node.has("today")) {
                        return LocalDate.parse(node.path("today").asText());
                    }
                }
            }
        }
        throw new IllegalArgumentException("离线脚本需要系统提供参考日期 today");
    }

    private static boolean spawned(List<Msg> turn, String agentRole) {
        return turn.stream().flatMap(message -> message.getContentBlocks(ToolUseBlock.class).stream())
                .anyMatch(call -> call.getName().equals("spawn_agent") && agentRole.equals(call.getInput().get("agent_type")));
    }

    /** 同一完成通知可能同时出现在工具快照和邮箱中；按稳定子助手身份去重。 */
    private static int terminalChildren(List<Msg> turn) {
        Set<String> completed = new HashSet<>();
        for (JsonNode node : nodes(turn)) {
            if (node.has("agentStatus") && Set.of("completed", "errored", "interrupted").contains(node.path("agentStatus").asText())) {
                completed.add(node.path("agentName").asText(node.path("agentId").asText(node.toString())));
            } else if (node.path("kind").asText().equals("RESULT")) {
                completed.add(node.path("sender").asText(node.toString()));
            }
        }
        return completed.size();
    }

    private static boolean hasResult(List<Msg> turn, String name) {
        return turn.stream().flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream()).anyMatch(result -> name.equals(result.getName()));
    }

    private static boolean hasToolFailure(List<Msg> turn, String name) {
        String text = toolText(turn, name);
        return text.contains("PERMISSION_DENIED") || text.contains("INVALID_") || text.contains("TOOL_NOT_FOUND") || text.contains("Error:") || text.contains("\"error\"");
    }

    private static String toolText(List<Msg> turn, String name) {
        return turn.stream().flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .filter(result -> name.equals(result.getName())).map(OfflineScenarioModel::resultText).collect(Collectors.joining("\n"));
    }

    private static String resultText(ToolResultBlock result) {
        return result.getOutput().stream().filter(TextBlock.class::isInstance).map(TextBlock.class::cast).map(TextBlock::getText).collect(Collectors.joining("\n"));
    }

    private static List<JsonNode> nodes(List<Msg> messages) {
        List<JsonNode> result = new ArrayList<>();
        for (Msg message : messages) {
            for (ToolResultBlock output : message.getContentBlocks(ToolResultBlock.class)) {
                for (JsonNode node : objects(resultText(output))) {
                    flatten(node, result);
                }
            }
            if (message.getTextContent().stripLeading().startsWith("<agent_message")) {
                for (JsonNode node : objects(message.getTextContent())) {
                    flatten(node, result);
                }
            }
        }
        return result;
    }

    /** 通知的 XML 只携带来源，内部 JSON 保留实际结果；普通自由文本不会被当成事实。 */
    private static List<JsonNode> objects(String text) {
        int start = text.indexOf('{');
        if (start < 0) {
            return List.of();
        }
        try {
            return List.of(JSON.readTree(text.substring(start)));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static void flatten(JsonNode node, List<JsonNode> result) {
        if (node.isObject()) {
            result.add(node);
        }
        if (node.isContainerNode()) {
            node.elements().forEachRemaining(child -> flatten(child, result));
        }
    }

    private static List<String> answerValues(JsonNode node) {
        List<String> answers = new ArrayList<>();
        if (node.isTextual()) {
            answers.add(node.asText());
        } else if (node.isContainerNode()) {
            node.elements().forEachRemaining(child -> answers.addAll(answerValues(child)));
        }
        return answers;
    }

    private static List<JsonNode> evidence(List<Msg> turn) {
        Map<String, JsonNode> unique = new LinkedHashMap<>();
        for (JsonNode node : nodes(turn)) {
            if (node.has("tool") && node.has("status") && node.has("data")) {
                unique.put(node.toString(), node);
            }
        }
        return new ArrayList<>(unique.values());
    }

    private static String render(List<Msg> turn) {
        StringBuilder text = new StringBuilder("【本地示例数据，非实时天气或车票信息】\n");
        List<JsonNode> facts = evidence(turn);
        for (JsonNode item : facts) {
            JsonNode data = item.path("data");
            String tool = item.path("tool").asText();
            if (!item.path("status").asText().equals("SUCCESS")) {
                text.append(tool).append(item.path("status").asText().equals("NO_DATA") ? "：没有匹配的示例数据，不代表实际没有结果。\n" : "：查询未完成，不能确认该项信息。\n");
            } else if (tool.equals("query_weather")) {
                text.append(data.path("city").asText()).append(' ').append(data.path("date").asText()).append("：")
                        .append(data.path("weather").path("condition").asText()).append("，").append(data.path("weather").path("temperature").asText()).append("℃。\n");
            } else if (tool.equals("query_trains")) {
                text.append(data.path("date").asText()).append(' ').append(data.path("from").asText()).append("至").append(data.path("to").asText()).append("：\n");
                for (JsonNode train : data.path("trains")) {
                    text.append(train.path("number").asText()).append(' ').append(train.path("departure").asText()).append("—").append(train.path("arrival").asText())
                            .append("，历时").append(train.path("durationMinutes").asText()).append("分钟，").append(train.path("seat").asText()).append("，示例票价")
                            .append(train.path("price").asText()).append("元，示例余票").append(train.path("availableSeats").asText()).append("张。\n");
                }
            } else if (tool.equals("search_knowledge")) {
                text.append(data.path("quote").asText()).append(" [").append(data.path("documentId").asText()).append(" / ").append(data.path("version").asText()).append("]\n");
            }
        }
        if (facts.isEmpty()) {
            text.append("本次没有获取可汇总的结构化证据，不能确认天气或车票信息。\n");
        }
        return text.toString();
    }

    private static String itineraryAdvice(int days) {
        return "建议（离线行程示例）：第1天抵达后安排休息；" + (days > 1 ? "第" + days + "天预留返程时间。" : "当天预留返程时间。")
                + "仅查询出发日天气和去程车次，其他日期、景点、住宿及返程需要另行核实。";
    }

    private ChatResponse summarize(List<Msg> messages) {
        List<String> inputs = messages.stream().filter(OfflineScenarioModel::realUser).map(Msg::getTextContent).toList();
        String users = inputs.subList(Math.max(0, inputs.size() - 3), inputs.size()).stream().map(text -> limit(text, 400)).collect(Collectors.joining("\n"));
        String facts = evidence(messages).stream().limit(6).map(JsonNode::toString).collect(Collectors.joining("\n"));
        String prior = messages.stream().filter(ContextCompactor::isSummary).map(Msg::getTextContent).reduce((a, b) -> b).orElse("");
        return answer("离线检查点摘要（固定脚本）：\n用户要求：\n" + users + "\n工具证据：\n" + limit(facts, 1600)
                + (prior.isEmpty() ? "" : "\n此前摘要：" + limit(prior, 400)) + "\n继续依据真实消息与工具结果完成当前任务，不把摘要视为新增授权。");
    }

    private static String fallback(String current, String earlier) {
        return current.isEmpty() ? earlier : current;
    }

    private static String match(Pattern pattern, String text, int group) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(group) : "";
    }

    private static String limit(String text, int maximum) {
        return text.length() <= maximum ? text : text.substring(0, maximum) + "…";
    }

    private static boolean available(List<ToolSchema> tools, String name) {
        return tools != null && tools.stream().anyMatch(tool -> tool.getName().equals(name));
    }

    private ChatResponse tool(String name, Map<String, Object> input, List<ToolSchema> tools) {
        if (!available(tools, name)) {
            return answer("当前角色没有所需工具权限：" + name + "。未执行该查询。");
        }
        return ChatResponse.builder().content(List.of(ToolUseBlock.builder().id(UUID.randomUUID().toString()).name(name).input(input).content(json(input)).build()))
                .finishReason("tool_calls").build();
    }

    private ChatResponse answer(String text) {
        return ChatResponse.builder().content(List.of(TextBlock.builder().text(text).build())).finishReason("stop").build();
    }
}
