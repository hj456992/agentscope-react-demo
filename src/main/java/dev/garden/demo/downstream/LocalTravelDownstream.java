package dev.garden.demo.downstream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 独立进程运行的本地只读出行下游。
 *
 * <p>用于在真实 HTTP 边界上演示 429 + Retry-After、5xx、超时和不可重试的 4xx。
 * 故障只作用于指定工具，并且可以只发生指定次数；数据仍是本地示例数据，不连接任何
 * 真实供应商。启动参数只来自命令行，不读取或输出密钥。
 */
public final class LocalTravelDownstream {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final String fault;
    private final String faultTool;
    private final AtomicInteger remaining;
    private final int retryAfterSeconds;
    private final long delayMs;
    private final String orderFault;
    private final AtomicInteger orderFailures;
    private final Path ordersFile;
    private final Map<String, Order> orders = new LinkedHashMap<>();

    private LocalTravelDownstream(
            String fault,
            String faultTool,
            int failures,
            int retryAfterSeconds,
            long delayMs,
            String orderFault,
            int orderFailures,
            Path ordersFile) {
        this.fault = fault;
        this.faultTool = faultTool;
        this.remaining = new AtomicInteger(Math.max(0, failures));
        this.retryAfterSeconds = Math.max(0, retryAfterSeconds);
        this.delayMs = Math.max(0, delayMs);
        this.orderFault = orderFault;
        this.orderFailures = new AtomicInteger(Math.max(0, orderFailures));
        this.ordersFile = ordersFile;
        loadOrders();
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        int port = Integer.parseInt(options.getOrDefault("port", "18080"));
        LocalTravelDownstream app =
                new LocalTravelDownstream(
                        options.getOrDefault("fault", "none"),
                        options.getOrDefault("tool", "query_weather"),
                        Integer.parseInt(options.getOrDefault("failures", "1")),
                        Integer.parseInt(options.getOrDefault("retry-after", "1")),
                        Long.parseLong(options.getOrDefault("delay-ms", "0")),
                        options.getOrDefault("order-fault", "none"),
                        Integer.parseInt(options.getOrDefault("order-failures", "1")),
                        Path.of(options.getOrDefault("orders-file", "data/orders.jsonl")));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/health", exchange -> app.reply(exchange, 200, Map.of("status", "UP")));
        server.createContext("/weather", exchange -> app.handle(exchange, "query_weather"));
        server.createContext("/trains", exchange -> app.handle(exchange, "query_trains"));
        server.createContext("/orders", app::orders);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println(
                "LocalTravelDownstream listening on http://127.0.0.1:"
                        + port
                        + " fault="
                        + app.fault
                        + " tool="
                        + app.faultTool
                        + " remaining="
                        + app.remaining.get()
                        + " orderFault="
                        + app.orderFault
                        + " orders="
                        + app.orders.size()
                        + " ordersFile="
                        + app.ordersFile);
    }

    private void handle(HttpExchange exchange, String tool) throws IOException {
        System.out.println("request " + exchange.getRequestMethod() + " " + tool);
        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            if (shouldFail(tool)) {
                Fault faulted = nextFault();
                if (faulted == null) {
                    exchange.close();
                    return;
                }
                if (faulted.delayMs > 0) {
                    Thread.sleep(faulted.delayMs);
                }
                if (faulted.status < 0 || "timeout".equals(faulted.code)) {
                    exchange.close();
                    return;
                }
                if (faulted.status == 429) {
                    exchange.getResponseHeaders().set("Retry-After", Long.toString(retryAfterSeconds));
                }
                reply(
                        exchange,
                        faulted.status,
                        Map.of(
                                "error",
                                Map.of(
                                        "code",
                                        faulted.code,
                                        "message",
                                        "本地下游受控故障")));
                return;
            }
            Map<String, String> query = query(exchange);
            if (tool.equals("query_weather")) {
                reply(exchange, 200, weather(query));
            } else {
                reply(exchange, 200, trains(query));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            exchange.close();
        }
    }

    private boolean shouldFail(String tool) {
        return fault != null
                && !"none".equals(fault)
                && tool.equals(faultTool)
                && (fault.endsWith("-always") || remaining.get() > 0);
    }

    private Fault nextFault() {
        if (!fault.endsWith("-always") && remaining.getAndDecrement() <= 0) {
            return null;
        }
        return switch (fault) {
            case "http429", "http429-once", "http429-always" ->
                    new Fault(429, "rate_limited", 0);
            case "http500", "http500-once", "http500-always" -> new Fault(503, "downstream_unavailable", 0);
            case "http400", "http400-always" -> new Fault(400, "invalid_request", 0);
            case "http404", "http404-always" -> new Fault(404, "not_found", 0);
            case "timeout", "timeout-once", "timeout-always" -> new Fault(200, "timeout", 8_000);
            case "reset", "reset-once", "reset-always" -> new Fault(-1, "reset", 0);
            default -> throw new IllegalArgumentException("未知故障档位：" + fault);
        };
    }

    private static Map<String, Object> weather(Map<String, String> query) {
        String city = query.getOrDefault("city", "");
        return switch (city) {
            case "杭州" -> Map.of("temperature", 18, "condition", "多云");
            case "上海" -> Map.of("temperature", 22, "condition", "晴");
            default -> Map.of();
        };
    }

    private static List<Map<String, Object>> trains(Map<String, String> query) {
        String from = query.getOrDefault("from", "");
        String to = query.getOrDefault("to", "");
        if (!from.equals("杭州") || !to.equals("上海")) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("number", "G-DEMO-001");
        row.put("departure", "08:00");
        row.put("arrival", "09:00");
        row.put("durationMinutes", 60);
        row.put("seat", "二等座");
        row.put("price", 80);
        row.put("availableSeats", 10);
        row.put("note", "虚构车次，不代表实际时刻或余票");
        rows.add(row);
        return rows;
    }

    /**
     * 本地真实下单接口。
     *
     * <p>{@code POST /orders} 按操作号持久幂等：同一操作号、同一参数返回原订单；
     * 同一操作号、不同参数返回 409。{@code GET /orders/{operationId}} 提供权威查询。
     * 故障档位可以只影响下单接口，并区分“提交前失败”和“落盘后响应失败”，后者用于
     * 制造账本 UNKNOWN 场景。
     */
    private void orders(HttpExchange exchange) throws IOException {
        System.out.println("request " + exchange.getRequestMethod() + " orders");
        try {
            String path = exchange.getRequestURI().getPath();
            if ("POST".equals(exchange.getRequestMethod()) && "/orders".equals(path)) {
                createOrder(exchange);
                return;
            }
            if ("GET".equals(exchange.getRequestMethod()) && path.startsWith("/orders/")) {
                String operationId =
                        URLDecoder.decode(
                                path.substring("/orders/".length()), StandardCharsets.UTF_8);
                synchronized (orders) {
                    Order order = orders.get(operationId);
                    if (order == null) {
                        reply(exchange, 404, Map.of("error", Map.of("code", "order_not_found")));
                        return;
                    }
                    reply(exchange, 200, orderBody(order));
                }
                return;
            }
            reply(exchange, 404, Map.of("error", Map.of("code", "not_found")));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            exchange.close();
        }
    }

    @SuppressWarnings("unchecked")
    private void createOrder(HttpExchange exchange) throws IOException, InterruptedException {
        Map<String, Object> body;
        try {
            body = JSON.readValue(exchange.getRequestBody(), Map.class);
        } catch (IOException error) {
            reply(exchange, 400, Map.of("error", Map.of("code", "invalid_json")));
            return;
        }
        String operationId = String.valueOf(body.getOrDefault("operationId", "")).strip();
        if (operationId.isBlank() || operationId.length() > 200) {
            reply(exchange, 400, Map.of("error", Map.of("code", "invalid_operation_id")));
            return;
        }
        Object parameters = body.get("parameters");
        if (!(parameters instanceof Map<?, ?>)) {
            reply(exchange, 400, Map.of("error", Map.of("code", "invalid_parameters")));
            return;
        }
        Map<String, Object> params = (Map<String, Object>) parameters;
        String hash;
        try {
            hash = JSON.writeValueAsString(new java.util.TreeMap<>(params));
        } catch (IOException error) {
            reply(exchange, 400, Map.of("error", Map.of("code", "invalid_parameters")));
            return;
        }
        Order created;
        synchronized (orders) {
            Order existing = orders.get(operationId);
            if (existing != null) {
                if (!existing.parametersHash.equals(hash)) {
                    reply(
                            exchange,
                            409,
                            Map.of(
                                    "error",
                                    Map.of(
                                            "code",
                                            "operation_parameter_conflict",
                                            "message",
                                            "同一操作号的参数不一致")));
                    return;
                }
                reply(exchange, 200, orderBody(existing));
                return;
            }
            if (!orderFaultAllowed()) {
                created = submitOrder(operationId, hash, params);
            } else {
                String mode = orderFaultGuard();
                if ("http429".equals(mode)) {
                    exchange.getResponseHeaders()
                            .set("Retry-After", Long.toString(retryAfterSeconds));
                    reply(exchange, 429, Map.of("error", Map.of("code", "rate_limited")));
                    return;
                }
                if ("http500-before".equals(mode)) {
                    reply(
                            exchange,
                            503,
                            Map.of("error", Map.of("code", "downstream_unavailable")));
                    return;
                }
                if ("http500-after".equals(mode)) {
                    created = submitOrder(operationId, hash, params);
                    reply(
                            exchange,
                            503,
                            Map.of("error", Map.of("code", "downstream_unavailable_after_commit")));
                    return;
                }
                if ("timeout-after".equals(mode)) {
                    created = submitOrder(operationId, hash, params);
                    System.out.println("order committed then connection closed " + created.operationId);
                    exchange.close();
                    return;
                }
                reply(exchange, 400, Map.of("error", Map.of("code", "order_fault_unknown")));
                return;
            }
        }
        reply(exchange, 201, orderBody(created));
    }

    private boolean orderFaultAllowed() {
        if (orderFault == null || "none".equals(orderFault)) {
            return false;
        }
        if (orderFault.endsWith("-always")) {
            return true;
        }
        return orderFailures.getAndDecrement() > 0;
    }

    private String orderFaultGuard() {
        return orderFault.endsWith("-once")
                ? orderFault.substring(0, orderFault.length() - "-once".length())
                : orderFault.endsWith("-always")
                        ? orderFault.substring(0, orderFault.length() - "-always".length())
                        : orderFault;
    }

    private Order submitOrder(
            String operationId, String parametersHash, Map<String, Object> parameters) {
        Order order = new Order();
        order.operationId = operationId;
        order.parametersHash = parametersHash;
        order.parameters = new LinkedHashMap<>(parameters);
        order.status = "CONFIRMED";
        order.orderNumber = "LOCAL-" + Integer.toHexString(operationId.hashCode());
        order.createdAt = System.currentTimeMillis();
        orders.put(operationId, order);
        appendOrder(order);
        return order;
    }

    private void loadOrders() {
        if (ordersFile == null || !Files.exists(ordersFile)) {
            return;
        }
        try {
            for (String line : Files.readAllLines(ordersFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Order order = JSON.readValue(line, Order.class);
                    if (order.operationId != null) {
                        orders.put(order.operationId, order);
                    }
                } catch (IOException ignored) {
                    // 单行损坏不阻断其余持久订单的恢复。
                }
            }
        } catch (IOException error) {
            throw new IllegalStateException("无法读取本地订单文件", error);
        }
    }

    private void appendOrder(Order order) {
        if (ordersFile == null) {
            return;
        }
        try {
            Path parent = ordersFile.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(
                    ordersFile,
                    JSON.writeValueAsString(order) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException error) {
            throw new IllegalStateException("无法写入本地订单文件", error);
        }
    }

    private static Map<String, Object> orderBody(Order order) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operationId", order.operationId);
        body.put("status", order.status);
        body.put("orderNumber", order.orderNumber);
        body.put("createdAt", order.createdAt);
        body.put("parametersHash", order.parametersHash);
        return body;
    }

    /** 本地订单的持久形态；字段名直接进入文件，不包含任何凭据。 */
    public static final class Order {
        public String operationId;
        public String parametersHash;
        public Map<String, Object> parameters;
        public String status;
        public String orderNumber;
        public long createdAt;
    }

    private void reply(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new LinkedHashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isBlank()) {
            return result;
        }
        for (String part : raw.split("&")) {
            int split = part.indexOf('=');
            String key = split < 0 ? part : part.substring(0, split);
            String value = split < 0 ? "" : part.substring(split + 1);
            result.put(
                    URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return result;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String arg : args) {
            if (!arg.startsWith("--")) {
                continue;
            }
            int split = arg.indexOf('=');
            if (split < 0) {
                result.put(arg.substring(2), "true");
            } else {
                result.put(arg.substring(2, split), arg.substring(split + 1));
            }
        }
        return result;
    }

    private record Fault(int status, String code, long delayMs) {}
}
