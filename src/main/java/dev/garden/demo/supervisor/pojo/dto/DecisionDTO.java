package dev.garden.demo.supervisor.pojo.dto;

/** 不适用的字符串字段返回空串；days 是行程天数。 */
public record DecisionDTO(
        Intent intent, String city, String from, String to, String date, int days) {}
