package dev.garden.demo.supervisor;

import dev.garden.demo.supervisor.pojo.dto.*;

import java.util.List;
import java.util.Map;

/** 把已核对的工具证据渲染成确定性答复，供事实冲突降级和可选语义校验复用。 */
final class EvidenceRenderer {
    private final RunContext context;

    EvidenceRenderer(RunContext context) {
        this.context = context;
    }

    String render() {
        StringBuilder result = new StringBuilder("【示例数据，非实时天气或车票信息】\n");
        Status status = context.outcome();
        if (status == Status.PARTIAL) {
            result.append("部分查询失败、缺少数据或尚未执行。\n");
        }
        if (status == Status.FAILED) {
            result.append("查询未完成，不能确认天气或车票情况。\n");
        }
        if (status == Status.NO_DATA) {
            result.append("没有匹配的示例数据，不代表实际无车票或无天气信息。\n");
        }
        for (EvidenceDTO evidence : context.evidence()) {
            Map<String, Object> d = evidence.data();
            if (evidence.status() != Status.SUCCESS) {
                result.append(evidence.tool())
                        .append("：")
                        .append(evidence.status() == Status.NO_DATA ? "无示例数据" : "失败")
                        .append('\n');
                continue;
            }
            if (evidence.tool().equals("query_weather")) {
                Map<?, ?> weather = (Map<?, ?>) d.get("weather");
                result.append(d.get("city"))
                        .append(' ')
                        .append(d.get("date"))
                        .append("：")
                        .append(weather.get("condition"))
                        .append("，")
                        .append(weather.get("temperature"))
                        .append("℃。\n");
            } else if (evidence.tool().equals("query_trains")) {
                result.append(d.get("date"))
                        .append(' ')
                        .append(d.get("from"))
                        .append("至")
                        .append(d.get("to"))
                        .append('：');
                for (Object item : (List<?>) d.get("trains")) {
                    Map<?, ?> train = (Map<?, ?>) item;
                    result.append(train.get("number"))
                            .append(' ')
                            .append(train.get("departure"))
                            .append("—")
                            .append(train.get("arrival"))
                            .append("，历时")
                            .append(train.get("durationMinutes"))
                            .append("分钟，")
                            .append(train.get("seat"))
                            .append("，示例票价")
                            .append(train.get("price"))
                            .append("元，示例余票")
                            .append(train.get("availableSeats"))
                            .append("张。\n");
                }
            } else if (evidence.tool().equals("search_knowledge")) {
                result.append("出行示例资料：")
                        .append(d.get("quote"))
                        .append(" [")
                        .append(d.get("documentId"))
                        .append(" / ")
                        .append(d.get("version"))
                        .append("]\n");
            }
        }
        if (context.decision.intent() == Intent.ITINERARY) {
            result.append("建议：第1天抵达后休息，根据兴趣安排活动。");
            if (context.decision.days() > 1) {
                result.append("第").append(context.decision.days()).append("天预留返程时间。");
            }
            result.append("仅查询了出发日的天气和去程车次；其他日期、景点、住宿及返程仍需核实。\n");
        }
        if (status == Status.BUDGET_EXHAUSTED) {
            result.append("调用预算已耗尽，以上仅展示已获取证据。");
        }
        return result.toString();
    }
}
