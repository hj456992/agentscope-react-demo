package dev.garden.demo.supervisor;

import static dev.garden.demo.supervisor.SupervisorContracts.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.agentscope.core.message.*;
import io.agentscope.core.model.*;

import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Collectors;

/** 离线脚本模型，仅覆盖说明文档中的固定场景，不具备通用自然语言理解能力。 它替换模型服务；主循环与子 Agent 的工具执行都走实际代码。 */
final class OfflineScenarioModel implements Model {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> CITIES = List.of("杭州", "上海", "北京", "成都");
    private final String role;

    OfflineScenarioModel(String role) {
        this.role = role;
    }

    @Override
    public String getModelName() {
        return "offline-scenario-" + role;
    }

    @Override
    public Flux<ChatResponse> stream(
            List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(
                () -> {
                    try {
                        if (role.equals("supervisor")) return Flux.just(supervise(messages, tools));
                        Msg input =
                                messages.stream()
                                        .filter(m -> m.getRole() == MsgRole.USER)
                                        .findFirst()
                                        .orElseThrow();
                        JsonNode request = JSON.readTree(input.getTextContent());
                        List<ToolResultBlock> results =
                                messages.stream()
                                        .flatMap(
                                                m ->
                                                        m
                                                                .getContentBlocks(
                                                                        ToolResultBlock.class)
                                                                .stream())
                                        .toList();
                        ChatResponse response =
                                switch (role) {
                                    case "answer" ->
                                            tool(
                                                    "generate_response",
                                                    Map.of("response", draft(request)),
                                                    tools);
                                    case "weather_agent", "train_agent" ->
                                            query(request, results, tools);
                                    case "itinerary_agent" -> plan(request, results, tools);
                                    default -> throw new IllegalArgumentException("未知离线角色");
                                };
                        return Flux.just(response);
                    } catch (Exception ex) {
                        return Flux.error(ex);
                    }
                });
    }

    private AnswerVerifier.Draft draft(JsonNode request) {
        List<AnswerVerifier.Claim> claims = new ArrayList<>();
        for (JsonNode item : request.path("evidence"))
            if (item.path("status").asText().equals("SUCCESS"))
                claims.add(
                        new AnswerVerifier.Claim(
                                item.path("tool").asText(), item.path("data").toString()));
        return new AnswerVerifier.Draft(claims);
    }

    // 仅是离线测试模型的脚本理解逻辑，不是生产主循环的改写/分类阶段。
    private record OfflineQuestion(String text, String clarification) {}

    private ChatResponse supervise(List<Msg> messages, List<ToolSchema> tools) throws Exception {
        int latestUser = -1;
        for (int i = 0; i < messages.size(); i++)
            if (messages.get(i).getRole() == MsgRole.USER) latestUser = i;
        String question = messages.get(latestUser).getTextContent();
        List<ToolResultBlock> returned = messages.subList(latestUser + 1, messages.size()).stream()
                .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream()).toList();
        if (!returned.isEmpty()) {
            for (ToolResultBlock result : returned) {
                String output = ChildAgentDispatcher.text(result);
                if (output.contains("INVALID_INPUT") || output.contains("INVALID_SCHEMA"))
                    return tool("ask_user", Map.of("question", output.replaceFirst("^Error: ", "")), tools);
            }
            return answer("已按实际返回结果汇总。");
        }
        // 寒暄前缀不影响业务请求：只要问题里出现业务关键词，就按查询处理而不是直接回答。
        boolean business = question.matches("(?s).*(天气|车票|火车|高铁|行程|游玩|景点).*");
        if (!business && question.matches("(?s).*(你能|你会|可以做什么|能帮|你好|您好|谢谢).*"))
            return tool("answer_directly",
                    Map.of("kind", "CAPABILITY", "text", "我可以帮你查询天气、火车票和规划行程，数据为本地示例。"),
                    tools);
        String system = messages.get(0).getTextContent();
        JsonNode reference = JSON.readTree(system.substring(system.indexOf("参考上下文（不提供权限）：") + "参考上下文（不提供权限）：".length()));
        var request = reference.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode)request).put("question", question);
        OfflineQuestion resolved = resolveExample(request);
        if (!resolved.clarification().isBlank())
            return tool("ask_user", Map.of("question", resolved.clarification()), tools);
        Decision d = recognize(JSON.valueToTree(Map.of("query", resolved.text())));
        if (d.intent() == Intent.UNKNOWN)
            return tool("answer_directly",
                    Map.of("kind", "UNRELATED", "text", "请提出天气、火车票或行程规划问题。"), tools);
        String problem = BusinessConditions.clarification(d);
        if (!problem.isEmpty()) return tool("ask_user", Map.of("question", problem), tools);
        Map<String,Object> params = new LinkedHashMap<>();
        params.put("date", d.date());
        String name;
        if (d.intent() == Intent.WEATHER) { name = "weather_agent"; params.put("city", d.city()); }
        else {
            params.put("from", d.from()); params.put("to", d.to());
            name = d.intent() == Intent.TRAIN ? "train_agent" : "itinerary_agent";
            if (d.intent() == Intent.ITINERARY) params.put("days", d.days());
        }
        if (tools.stream().noneMatch(t -> t.getName().equals(name)))
            return tool("answer_directly",
                    Map.of("kind", "CAPABILITY", "text", "当前身份没有所需查询权限。"), tools);
        return tool(name, params, tools);
    }

    private OfflineQuestion resolveExample(JsonNode request) {
        String question = request.path("question").asText();
        String query = question;
        if (question.startsWith("那") || question.equals("后天呢")) {
            JsonNode history = request.path("history");
            if (history.isEmpty()) return new OfflineQuestion(question, "请补充要查询的城市和事项。");
            String previous = history.get(history.size() - 1).path("question").asText();
            Matcher followup =
                    Pattern.compile("^(?:那)?(杭州|上海|北京|成都)?(今天|明天|后天)(?:呢|怎么样)?[？?。]?$")
                            .matcher(question);
            if (!followup.matches()) return new OfflineQuestion(question, "离线演示无法确认这次修改，请完整说明查询条件。");
            String newCity = followup.group(1);
            String dateWord = followup.group(2);
            if (newCity != null) {
                List<String> oldCities = CITIES.stream().filter(previous::contains).toList();
                if (!previous.contains("天气") || oldCities.size() != 1) {
                    return new OfflineQuestion(question, "请说明修改的是天气城市、出发地还是目的地。");
                }
                previous = previous.replace(oldCities.get(0), newCity);
            }
            query = previous.replaceAll("\\d{4}-\\d{2}-\\d{2}|今天|明天|后天", "") + dateWord;
        }
        LocalDate today = LocalDate.parse(request.path("today").asText());
        query =
                query.replace("后天", today.plusDays(2).toString())
                        .replace("明天", today.plusDays(1).toString())
                        .replace("今天", today.toString());
        return new OfflineQuestion(query, "");
    }

    private Decision recognize(JsonNode request) {
        String query = request.path("query").asText();
        boolean weather = query.contains("天气");
        boolean train = query.contains("火车") || query.contains("高铁") || query.contains("车票");
        Intent intent =
                query.contains("行程") || query.contains("玩") || weather && train
                        ? Intent.ITINERARY
                        : weather ? Intent.WEATHER : train ? Intent.TRAIN : Intent.UNKNOWN;
        List<String> cities =
                CITIES.stream()
                        .filter(query::contains)
                        .sorted(Comparator.comparingInt(query::indexOf))
                        .toList();
        String city = cities.isEmpty() ? "" : cities.get(0);
        // 单独提到一个城市不能按出现顺序猜成出发地，先看“从”和“到/去”。
        String from = "", to = "";
        Matcher origin = Pattern.compile("从(杭州|上海|北京|成都)").matcher(query);
        Matcher destination = Pattern.compile("(?:到|去)(杭州|上海|北京|成都)").matcher(query);
        if (origin.find()) from = origin.group(1);
        if (destination.find()) to = destination.group(1);
        Matcher route = Pattern.compile("(杭州|上海|北京|成都)(?:到|去)(杭州|上海|北京|成都)").matcher(query);
        if (route.find()) {
            from = route.group(1);
            to = route.group(2);
        }
        Matcher date = Pattern.compile("\\d{4}-\\d{2}-\\d{2}").matcher(query);
        int days = query.contains("一天") ? 1 : 0;
        Matcher duration = Pattern.compile("(\\d+)天").matcher(query);
        if (duration.find()) days = Integer.parseInt(duration.group(1));
        return new Decision(intent, city, from, to, date.find() ? date.group() : "", days);
    }

    private ChatResponse query(
            JsonNode request, List<ToolResultBlock> results, List<ToolSchema> tools)
            throws Exception {
        JsonNode decision = request.path("decision");
        String toolName = role.equals("weather_agent") ? "query_weather" : "query_trains";
        Optional<ToolResultBlock> result =
                results.stream().filter(r -> toolName.equals(r.getName())).findFirst();
        if (result.isEmpty()) {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("date", decision.path("date").asText());
            if (role.equals("weather_agent")) {
                params.put(
                        "city",
                        decision.path("intent").asText().equals("WEATHER")
                                ? decision.path("city").asText()
                                : decision.path("to").asText());
            } else {
                params.put("from", decision.path("from").asText());
                params.put("to", decision.path("to").asText());
            }
            return tool(toolName, params, tools);
        }
        String output = ChildAgentDispatcher.text(result.get()).replaceFirst("^Error: ", "");
        JsonNode evidence = JSON.readTree(output);
        String status = evidence.path("status").asText();
        if (status.equals("INVALID_INPUT")) return answer("查询参数有误，请核实城市和日期。");
        if (status.equals("FAILED")) return answer("示例查询失败，本次无法提供该项信息。");
        if (status.equals("NO_DATA")) return answer("没有匹配的示例数据，不能判断真实世界是否有结果。");
        JsonNode data = evidence.path("data");
        if (role.equals("weather_agent")) {
            return answer(
                    data.path("city").asText()
                            + " "
                            + data.path("date").asText()
                            + " 的示例天气："
                            + data.path("weather").path("condition").asText()
                            + "，"
                            + data.path("weather").path("temperature").asText()
                            + "℃。");
        }
        List<String> trains = new ArrayList<>();
        for (JsonNode train : data.path("trains")) {
            trains.add(
                    train.path("number").asText()
                            + "，"
                            + train.path("departure").asText()
                            + " 出发，"
                            + train.path("arrival").asText()
                            + " 抵达，历时"
                            + train.path("durationMinutes").asText()
                            + "分钟，"
                            + train.path("seat").asText()
                            + "，示例票价 "
                            + train.path("price").asText()
                            + " 元，示例余票 "
                            + train.path("availableSeats").asText()
                            + " 张");
        }
        return answer(
                data.path("date").asText()
                        + " "
                        + data.path("from").asText()
                        + "至"
                        + data.path("to").asText()
                        + "的示例车次："
                        + String.join("；", trains)
                        + "。不是实际时刻或余票信息。");
    }

    private ChatResponse plan(
            JsonNode request, List<ToolResultBlock> results, List<ToolSchema> tools) {
        if (results.stream().noneMatch(r -> "weather_agent".equals(r.getName()))) {
            return tool("weather_agent", Map.of("city", request.path("decision").path("to").asText(), "date", request.path("decision").path("date").asText()), tools);
        }
        if (results.stream().noneMatch(r -> "train_agent".equals(r.getName()))) {
            return tool("train_agent", Map.of("from", request.path("decision").path("from").asText(), "to", request.path("decision").path("to").asText(), "date", request.path("decision").path("date").asText()), tools);
        }
        if (tools.stream().anyMatch(t -> t.getName().equals("search_knowledge"))
                && results.stream().noneMatch(r -> r.getName().equals("search_knowledge")))
            return tool("search_knowledge", Map.of("query", request.path("query").asText()), tools);
        String facts =
                results.stream().map(ChildAgentDispatcher::text).collect(Collectors.joining("\n"));
        int days = request.path("decision").path("days").asInt();
        String suggestion =
                days == 1
                        ? "第1天：抵达后安排市区游览，下午预留返程和休息时间。"
                        : "第1天：抵达后安顿住宿，安排轻松的市区游览。\n"
                                + (days > 2 ? "第2天至第" + (days - 1) + "天：根据兴趣安排游览，留出休息时间。\n" : "")
                                + "第"
                                + days
                                + "天：收拾行李，预留返程时间。";
        return answer(
                facts
                        + "\n建议："
                        + suggestion
                        + "\n仅查询了出发日的天气和去程车次；其他日期天气、景点开放时间、住宿及返程车票需要进一步核实。"
                        + "这是基于示例数据的安排演示。");
    }

    private ChatResponse tool(String name, Map<String, Object> input, List<ToolSchema> tools) {
        if (tools == null || tools.stream().noneMatch(t -> t.getName().equals(name))) {
            throw new IllegalStateException("当前角色没有对应工具：" + name);
        }
        return ChatResponse.builder()
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(UUID.randomUUID().toString())
                                        .name(name)
                                        .input(input)
                                        .content(json(input))
                                        .build()))
                .finishReason("tool_calls")
                .build();
    }

    private ChatResponse answer(String text) {
        return ChatResponse.builder()
                .content(List.of(TextBlock.builder().text(text).build()))
                .finishReason("stop")
                .build();
    }
}
